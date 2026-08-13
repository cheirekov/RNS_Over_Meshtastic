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
    private static final int MAX_PENDING_WRITES = 64;

    private final Context context;
    private final BridgeConfig config;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger packetId = new AtomicInteger(new SecureRandom().nextInt());
    // Nonce 1 is a firmware sentinel that forces a NodeInfo LoRa broadcast.
    private final AtomicInteger heartbeat = new AtomicInteger(1);
    private final Queue<byte[]> writes = new ArrayDeque<>();
    private final DeviceQueueFlowControl deviceQueue = new DeviceQueueFlowControl();
    private final ReconnectBackoff reconnectBackoff = new ReconnectBackoff(5_000, 60_000);
    private final Runnable reconnect = this::connect;
    private volatile boolean closed;
    private volatile long localNode;
    private volatile boolean mqttUplinkPermitted;
    private volatile BluetoothGatt gatt;
    private BluetoothGattCharacteristic toRadio;
    private BluetoothGattCharacteristic fromRadio;
    private boolean writeBusy;
    private boolean readBusy;
    private boolean nodeInfoRequested;
    private boolean profileConfigured;
    private int fromRadioCount;
    private Listener listener;

    BlePhoneApiTransport(Context context, BridgeConfig config) {
        this.context = context.getApplicationContext();
        this.config = config;
    }

    @Override public void start(Listener listener) {
        this.listener = listener;
        main.post(this::connect);
        scheduler.scheduleWithFixedDelay(this::sendHeartbeat, 30, 30, TimeUnit.SECONDS);
    }

    private void connect() {
        if (closed) return;
        main.removeCallbacks(reconnect);
        if (gatt != null) return;
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
                listener.onRadioState(true, "BLE GATT connected; discovering Meshtastic service");
                if (!current.discoverServices()) {
                    listener.onRadioState(false, "Android rejected BLE service discovery");
                    current.disconnect();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                listener.onRadioState(false, "BLE disconnected (status " + status + ")");
                reset(current);
                retry();
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onRadioState(false, "BLE connection failed (status " + status + ")");
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
            listener.onRadioState(true, "Meshtastic BLE service found; negotiating MTU");
            profileConfigured = false;
            if (!current.requestMtu(512)) configureProfile(current, "MTU request rejected; using current MTU");
            else main.postDelayed(() -> configureProfile(current, "MTU callback timeout; using negotiated/default MTU"), 2_000);
        }

        @Override public void onMtuChanged(BluetoothGatt current, int mtu, int status) {
            configureProfile(current, "BLE MTU " + mtu + " (status " + status + ")");
        }

        private void configureProfile(BluetoothGatt current, String mtuDetail) {
            if (profileConfigured || current != gatt) return;
            profileConfigured = true;
            listener.onRadioState(true, mtuDetail + "; enabling FromNum notifications");
            BluetoothGattService service = current.getService(SERVICE);
            BluetoothGattCharacteristic fromNum = service == null ? null : service.getCharacteristic(FROM_NUM);
            toRadio = service == null ? null : service.getCharacteristic(TO_RADIO);
            fromRadio = service == null ? null : service.getCharacteristic(FROM_RADIO);
            if (fromNum == null || toRadio == null || fromRadio == null) {
                listener.onRadioState(false, "Device does not expose the Meshtastic BLE PhoneAPI");
                current.disconnect();
                return;
            }
            if (!current.setCharacteristicNotification(fromNum, true)) {
                listener.onRadioState(false, "Android rejected local FromNum notification setup");
                current.disconnect();
                return;
            }
            BluetoothGattDescriptor descriptor = fromNum.getDescriptor(CCCD);
            if (descriptor == null) {
                listener.onRadioState(false, "Meshtastic FromNum notification descriptor is missing");
                current.disconnect();
                return;
            }
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!current.writeDescriptor(descriptor)) {
                listener.onRadioState(false, "Android rejected the FromNum CCCD write");
                current.disconnect();
            }
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
            main.postDelayed(this::drainAfterSubscription, 500);
            main.postDelayed(() -> {
                if (!closed && localNode == 0 && current == gatt) {
                    listener.onRadioState(false, "BLE handshake timeout: no MyNodeInfo after 20 seconds; reconnecting");
                    current.disconnect();
                }
            }, 20_000);
        }

        private void drainAfterSubscription() { drainFromRadio(); }

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
            if (status == BluetoothGatt.GATT_SUCCESS && value != null && value.length > 0) {
                fromRadioCount++;
                handleFromRadio(value);
                boolean pendingWrite;
                synchronized (writes) {
                    pendingWrite = !writes.isEmpty();
                    if (pendingWrite) writeNextLocked();
                }
                if (!pendingWrite) drainFromRadio();
            } else {
                synchronized (writes) { writeNextLocked(); }
            }
        }

        @Override public void onCharacteristicWrite(BluetoothGatt current, BluetoothGattCharacteristic characteristic, int status) {
            synchronized (writes) {
                writeBusy = false;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onRadioState(false, "BLE PhoneAPI write failed: " + status);
                }
                if (writes.isEmpty()) main.postDelayed(BlePhoneApiTransport.this::drainFromRadio, 200);
                else writeNextLocked();
            }
        }
    };

    private void handleFromRadio(byte[] protobuf) {
        try {
            ProtoCodec.FromRadio message = ProtoCodec.parseFromRadio(protobuf);
            if (message.myNodeNumber != null) {
                localNode = message.myNodeNumber;
                reconnectBackoff.reset();
                listener.onLocalNode(localNode);
                listener.onRadioState(true, "BLE received MyNodeInfo as " + NodeId.format(localNode)
                        + " after " + fromRadioCount + " FromRadio frames");
            }
            if (message.configOkToMqtt != null) {
                mqttUplinkPermitted = message.configOkToMqtt;
                listener.onRadioState(true, "Radio MQTT uplink permission: " + mqttUplinkPermitted);
            }
            if (message.queueStatus != null) {
                deviceQueue.update(message.queueStatus);
                listener.onQueueStatus(
                        message.queueStatus.free, message.queueStatus.maxLength, message.queueStatus.result);
            }
            if (message.configCompleteId != null
                    && message.configCompleteId == CONFIG_NONCE
                    && !nodeInfoRequested) {
                nodeInfoRequested = true;
                enqueue(ProtoCodec.wantConfig(NODE_INFO_NONCE));
                listener.onRadioState(true, "BLE config loaded; reading node database");
            } else if (message.configCompleteId != null && message.configCompleteId == NODE_INFO_NONCE) {
                listener.onRadioState(true, "BLE PhoneAPI handshake complete as " + NodeId.format(localNode)
                        + "; MQTT uplink permission: " + mqttUplinkPermitted);
            }
            if (message.packet != null && message.packet.port == ProtoCodec.RETICULUM_PORT) listener.onPacket(message.packet);
        } catch (IllegalArgumentException ignored) {}
    }

    private void drainFromRadio() {
        BluetoothGatt current = gatt;
        BluetoothGattCharacteristic characteristic = fromRadio;
        synchronized (writes) {
            if (closed || current == null || characteristic == null || readBusy || writeBusy || !writes.isEmpty()) return;
            readBusy = true;
            if (!current.readCharacteristic(characteristic)) {
                readBusy = false;
                listener.onRadioState(false, "Android rejected a FromRadio read; will retry on the next wake signal");
            }
        }
    }

    private boolean enqueue(byte[] protobuf) {
        if (closed) return false;
        synchronized (writes) {
            if (writes.size() >= MAX_PENDING_WRITES) return false;
            writes.add(protobuf);
            writeNextLocked();
            return true;
        }
    }

    private void sendHeartbeat() {
        if (closed || gatt == null || toRadio == null) return;
        enqueue(ProtoCodec.heartbeat(heartbeat.incrementAndGet()));
    }

    private void writeNextLocked() {
        BluetoothGatt current = gatt;
        if (writeBusy || readBusy || current == null || toRadio == null || writes.isEmpty()) return;
        byte[] value = writes.remove();
        toRadio.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        toRadio.setValue(value);
        writeBusy = current.writeCharacteristic(toRadio);
        if (!writeBusy) listener.onRadioState(false, "BLE stack rejected a PhoneAPI write");
    }

    @Override public void send(byte[] payload, long destination) throws Exception {
        if (localNode == 0) throw new IllegalStateException("radio identity is not available yet");
        int id = packetId.updateAndGet(previous -> previous == -1 ? 1 : previous + 1);
        byte[] message = ProtoCodec.toRadioPacket(
                localNode, destination, id, config.channel, config.hops,
                config.wantAck && destination != NodeId.BROADCAST,
                mqttUplinkPermitted, payload);
        if (!deviceQueue.acquire(45_000)) throw new IllegalStateException("Meshtastic device TX queue unavailable or full for 45 seconds");
        if (!enqueue(message)) {
            deviceQueue.releaseAfterLocalFailure();
            throw new IllegalStateException("BLE PhoneAPI queue is full");
        }
    }

    @Override public boolean isReady() { return localNode != 0; }

    private void reset(BluetoothGatt current) {
        try { current.close(); } catch (Exception ignored) {}
        if (gatt == current) gatt = null;
        toRadio = null;
        fromRadio = null;
        localNode = 0;
        mqttUplinkPermitted = false;
        nodeInfoRequested = false;
        profileConfigured = false;
        fromRadioCount = 0;
        readBusy = false;
        deviceQueue.reset();
        synchronized (writes) {
            writeBusy = false;
            writes.clear();
        }
    }

    private void retry() {
        if (closed) return;
        main.removeCallbacks(reconnect);
        long delay = reconnectBackoff.nextDelayMillis();
        listener.onRadioState(false, "BLE reconnect scheduled in " + (delay / 1_000) + " seconds");
        main.postDelayed(reconnect, delay);
    }

    @Override public void close() {
        closed = true;
        deviceQueue.close();
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
