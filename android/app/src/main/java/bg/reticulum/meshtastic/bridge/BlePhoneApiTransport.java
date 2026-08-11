package bg.reticulum.meshtastic.bridge;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Direct Meshtastic BLE PhoneAPI transport using the firmware GATT profile. */
@SuppressLint("MissingPermission")
@SuppressWarnings("deprecation")
final class BlePhoneApiTransport implements RadioTransport {
    private static final UUID SERVICE = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd");
    private static final UUID TO_RADIO = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7");
    private static final UUID FROM_NUM = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453");
    private static final UUID FROM_RADIO = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int CONFIG_NONCE = 69420;
    private static final int NODE_INFO_NONCE = 69421;

    private final Context context;
    private final BridgeConfig config;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger packetId = new AtomicInteger(new SecureRandom().nextInt());
    private final AtomicInteger heartbeat = new AtomicInteger();
    private final Queue<byte[]> writes = new ArrayDeque<>();
    private volatile boolean closed;
    private volatile long localNode;
    private volatile BluetoothGatt gatt;
    private BluetoothGattCharacteristic toRadio;
    private BluetoothGattCharacteristic fromRadio;
    private boolean writeBusy;
    private boolean readBusy;
    private boolean nodeInfoRequested;
    private Listener listener;

    BlePhoneApiTransport(Context context, BridgeConfig config) {
        this.context = context.getApplicationContext();
        this.config = config;
    }

    @Override public void start(Listener listener) {
        this.listener = listener;
        main.post(this::connect);
        scheduler.scheduleAtFixedRate(
                () -> enqueue(ProtoCodec.heartbeat(heartbeat.incrementAndGet())),
                20, 20, TimeUnit.SECONDS);
    }

    private void connect() {
        if (closed) return;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            listener.onRadioState(false, "Bluetooth is disabled");
            retry();
            return;
        }
        try {
            BluetoothDevice device = adapter.getRemoteDevice(config.bleAddress);
            listener.onRadioState(false, "Connecting BLE " + config.bleAddress);
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception error) {
            listener.onRadioState(false, "BLE: " + useful(error));
            retry();
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt current, int status, int newState) {
            if (closed) return;
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onRadioState(true, "BLE connected; discovering PhoneAPI");
                current.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                listener.onRadioState(false, "BLE disconnected (status " + status + ")");
                reset(current);
                retry();
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt current, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onRadioState(false, "BLE service discovery failed: " + status);
                current.disconnect();
                return;
            }
            if (!current.requestMtu(512)) configureProfile(current);
        }

        @Override public void onMtuChanged(BluetoothGatt current, int mtu, int status) {
            configureProfile(current);
        }

        private void configureProfile(BluetoothGatt current) {
            BluetoothGattService service = current.getService(SERVICE);
            BluetoothGattCharacteristic fromNum = service == null ? null : service.getCharacteristic(FROM_NUM);
            toRadio = service == null ? null : service.getCharacteristic(TO_RADIO);
            fromRadio = service == null ? null : service.getCharacteristic(FROM_RADIO);
            if (fromNum == null || toRadio == null || fromRadio == null) {
                listener.onRadioState(false, "Device does not expose the Meshtastic BLE PhoneAPI");
                current.disconnect();
                return;
            }
            current.setCharacteristicNotification(fromNum, true);
            BluetoothGattDescriptor descriptor = fromNum.getDescriptor(CCCD);
            if (descriptor == null) {
                listener.onRadioState(false, "Meshtastic FromNum notification descriptor is missing");
                current.disconnect();
                return;
            }
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            current.writeDescriptor(descriptor);
        }

        @Override public void onDescriptorWrite(BluetoothGatt current, BluetoothGattDescriptor descriptor, int status) {
            if (!CCCD.equals(descriptor.getUuid())) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onRadioState(false, "Could not enable BLE PhoneAPI notifications: " + status);
                current.disconnect();
                return;
            }
            listener.onRadioState(true, "BLE PhoneAPI ready; reading node identity");
            enqueue(ProtoCodec.heartbeat(heartbeat.incrementAndGet()));
            main.postDelayed(() -> enqueue(ProtoCodec.wantConfig(CONFIG_NONCE)), 200);
            drainFromRadio();
        }

        @Override public void onCharacteristicChanged(BluetoothGatt current, BluetoothGattCharacteristic characteristic) {
            if (FROM_NUM.equals(characteristic.getUuid())) drainFromRadio();
        }

        @Override public void onCharacteristicChanged(
                BluetoothGatt current, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (FROM_NUM.equals(characteristic.getUuid())) drainFromRadio();
        }

        @Override public void onCharacteristicRead(BluetoothGatt current, BluetoothGattCharacteristic characteristic, int status) {
            if (!FROM_RADIO.equals(characteristic.getUuid())) return;
            readBusy = false;
            byte[] value = status == BluetoothGatt.GATT_SUCCESS ? characteristic.getValue() : null;
            finishRead(status, value);
        }

        @Override public void onCharacteristicRead(
                BluetoothGatt current, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
            if (!FROM_RADIO.equals(characteristic.getUuid())) return;
            readBusy = false;
            finishRead(status, value);
        }

        private void finishRead(int status, byte[] value) {
            if (value != null && value.length > 0) {
                handleFromRadio(value);
                drainFromRadio();
            }
        }

        @Override public void onCharacteristicWrite(BluetoothGatt current, BluetoothGattCharacteristic characteristic, int status) {
            synchronized (writes) {
                writeBusy = false;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onRadioState(false, "BLE PhoneAPI write failed: " + status);
                }
                writeNextLocked();
            }
        }
    };

    private void handleFromRadio(byte[] protobuf) {
        try {
            ProtoCodec.FromRadio message = ProtoCodec.parseFromRadio(protobuf);
            if (message.myNodeNumber != null) {
                localNode = message.myNodeNumber;
                listener.onLocalNode(localNode);
            }
            if (message.configCompleteId != null
                    && message.configCompleteId == CONFIG_NONCE
                    && !nodeInfoRequested) {
                nodeInfoRequested = true;
                enqueue(ProtoCodec.wantConfig(NODE_INFO_NONCE));
                listener.onRadioState(true, "BLE config loaded; reading node database");
            } else if (message.configCompleteId != null && message.configCompleteId == NODE_INFO_NONCE) {
                listener.onRadioState(true, "BLE PhoneAPI handshake complete as " + NodeId.format(localNode));
            }
            if (message.packet != null && message.packet.port == ProtoCodec.RETICULUM_PORT) listener.onPacket(message.packet);
        } catch (IllegalArgumentException ignored) {}
    }

    private void drainFromRadio() {
        BluetoothGatt current = gatt;
        BluetoothGattCharacteristic characteristic = fromRadio;
        if (closed || current == null || characteristic == null || readBusy) return;
        readBusy = true;
        if (!current.readCharacteristic(characteristic)) readBusy = false;
    }

    private void enqueue(byte[] protobuf) {
        if (closed) return;
        synchronized (writes) {
            writes.add(protobuf);
            writeNextLocked();
        }
    }

    private void writeNextLocked() {
        BluetoothGatt current = gatt;
        if (writeBusy || current == null || toRadio == null || writes.isEmpty()) return;
        byte[] value = writes.remove();
        toRadio.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        toRadio.setValue(value);
        writeBusy = current.writeCharacteristic(toRadio);
        if (!writeBusy) listener.onRadioState(false, "BLE stack rejected a PhoneAPI write");
    }

    @Override public void send(byte[] payload, long destination) {
        if (localNode == 0) throw new IllegalStateException("radio identity is not available yet");
        int id = packetId.updateAndGet(previous -> previous == -1 ? 1 : previous + 1);
        enqueue(ProtoCodec.toRadioPacket(
                localNode, destination, id, config.channel, config.hops,
                config.wantAck && destination != NodeId.BROADCAST, payload));
    }

    private void reset(BluetoothGatt current) {
        try { current.close(); } catch (Exception ignored) {}
        if (gatt == current) gatt = null;
        toRadio = null;
        fromRadio = null;
        localNode = 0;
        nodeInfoRequested = false;
        readBusy = false;
        synchronized (writes) {
            writeBusy = false;
            writes.clear();
        }
    }

    private void retry() { if (!closed) main.postDelayed(this::connect, 5_000); }

    @Override public void close() {
        closed = true;
        scheduler.shutdownNow();
        main.removeCallbacksAndMessages(null);
        BluetoothGatt current = gatt;
        if (current != null) {
            try { current.disconnect(); } catch (Exception ignored) {}
            reset(current);
        }
    }

    private static String useful(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
