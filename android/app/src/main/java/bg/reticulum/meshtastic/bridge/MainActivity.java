package bg.reticulum.meshtastic.bridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.text.InputType;
import android.net.Uri;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MainActivity extends Activity {
    private Spinner transport;
    private EditText radioHost;
    private EditText radioPort;
    private EditText bleAddress;
    private Button bleScan;
    private EditText localPort;
    private EditText channel;
    private EditText hops;
    private Spinner mode;
    private EditText gateway;
    private EditText allowedSources;
    private EditText fragmentBody;
    private EditText txInterval;
    private Spinner ackPolicy;
    private TextView status;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String value = intent.getStringExtra(BridgeService.EXTRA_STATUS);
            if (value != null) status.setText(value);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
        load();
    }

    private View buildUi() {
        int padding = dp(16);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("Reticulum ↔ Meshtastic");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        form.addView(title);

        TextView explanation = new TextView(this);
        explanation.setText("Local Reticulum TCP server for Sideband/Columba, bound only to 127.0.0.1. "
                + "Clients may display the local TCP estimate of 10 Mbps; actual radio traffic is paced below.");
        explanation.setPadding(0, dp(6), 0, dp(12));
        form.addView(explanation);

        transport = spinner(new String[] {"tcp", "ble"});
        add(form, "Radio transport", transport);
        radioHost = text("172.16.16.115", false);
        add(form, "Meshtastic TCP host", radioHost);
        radioPort = text("4403", true);
        add(form, "Meshtastic TCP port", radioPort);
        bleAddress = text("", false);
        add(form, "Meshtastic BLE MAC address", bleAddress);
        bleScan = new Button(this);
        bleScan.setText("Scan for Meshtastic BLE radios");
        bleScan.setOnClickListener(ignored -> scanBle());
        form.addView(bleScan);
        localPort = text("7822", true);
        add(form, "Local Reticulum TCP port", localPort);
        channel = text("0", true);
        add(form, "Meshtastic channel index (HQ = 0)", channel);
        hops = text("3", true);
        add(form, "Hop limit", hops);
        mode = spinner(new String[] {"gateway_unicast", "broadcast"});
        add(form, "Radio addressing mode", mode);
        gateway = text("!8fd13c64", false);
        add(form, "Unicast peer / gateway Meshtastic Node ID", gateway);
        allowedSources = text("!aabbcc11, !11223344", false);
        add(form, "Allowed peer Node IDs (optional, broadcast only)", allowedSources);
        fragmentBody = text("200", true);
        add(form, "Fragment payload bytes", fragmentBody);
        txInterval = text("2000", true);
        add(form, "Global delay between Meshtastic transmissions (ms)", txInterval);
        ackPolicy = spinner(new String[] {"off", "critical", "all (diagnostic only)"});
        add(form, "Meshtastic radio ACK policy (not an LXMF receipt)", ackPolicy);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button start = new Button(this);
        start.setText("Save & start");
        start.setOnClickListener(ignored -> saveAndStart());
        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(ignored -> stopService(new Intent(this, BridgeService.class)));
        buttons.addView(start, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        buttons.addView(stop, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        form.addView(buttons);

        Button backgroundSettings = new Button(this);
        backgroundSettings.setText("Open background / battery settings");
        backgroundSettings.setOnClickListener(ignored -> openBackgroundSettings());
        form.addView(backgroundSettings);

        status = new TextView(this);
        status.setText("Bridge is not running");
        status.setTextIsSelectable(true);
        status.setPadding(0, dp(16), 0, dp(24));
        form.addView(status);

        transport.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateTransportFields();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateModeFields();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        return scroll;
    }

    private void load() {
        BridgeConfig config = BridgeConfig.load(this);
        select(transport, config.transport);
        radioHost.setText(config.radioHost);
        radioPort.setText(String.valueOf(config.radioPort));
        bleAddress.setText(config.bleAddress);
        localPort.setText(String.valueOf(config.localPort));
        channel.setText(String.valueOf(config.channel));
        hops.setText(String.valueOf(config.hops));
        select(mode, config.mode);
        gateway.setText(config.gateway);
        allowedSources.setText(config.allowedSources);
        fragmentBody.setText(String.valueOf(config.fragmentBody));
        txInterval.setText(String.valueOf(config.txIntervalMillis));
        select(ackPolicy, config.ackPolicy.equals("all") ? "all (diagnostic only)" : config.ackPolicy);
        status.setText(BridgeService.latestStatus(this));
        updateTransportFields();
        updateModeFields();
    }

    private void saveAndStart() {
        try {
            String selectedTransport = selected(transport);
            BridgeConfig config = new BridgeConfig(
                    selectedTransport,
                    selectedTransport.equals("tcp") ? value(radioHost) : "",
                    selectedTransport.equals("tcp") ? integer(radioPort) : 4403,
                    selectedTransport.equals("ble") ? value(bleAddress) : "",
                    integer(localPort), integer(channel), integer(hops), selected(mode), value(gateway),
                    integer(fragmentBody), integer(txInterval), ackPolicyValue(), value(allowedSources));
            if (!ensurePermissions(config)) return;
            config.save(this);
            Intent service = new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_START);
            startForegroundService(service);
            status.setText("Starting bridge…");
        } catch (Exception error) {
            status.setText("Configuration error: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        }
    }

    private boolean ensurePermissions(BridgeConfig config) {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (config.transport.equals("ble") && Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (config.transport.equals("ble") && Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (config.transport.equals("ble") && Build.VERSION.SDK_INT <= 30
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (missing.isEmpty()) return true;
        requestPermissions(missing.toArray(new String[0]), 76);
        status.setText("Grant the requested permission, then press Save & start again.");
        return false;
    }

    private void scanBle() {
        if (Build.VERSION.SDK_INT >= 31
                && (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 77);
            status.setText("Grant Nearby devices, then press Scan again.");
            return;
        }
        if (Build.VERSION.SDK_INT <= 30
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION}, 77);
            status.setText("Grant location for BLE discovery, then press Scan again.");
            return;
        }
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled() || adapter.getBluetoothLeScanner() == null) {
            status.setText("Bluetooth is disabled or BLE is unavailable.");
            return;
        }
        Map<String, String> found = new LinkedHashMap<>();
        ScanCallback callback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
                String address = result.getDevice().getAddress();
                found.put(address, (name == null ? "Meshtastic" : name) + " — " + address);
            }

            @Override public void onScanFailed(int errorCode) { status.setText("BLE scan failed: " + errorCode); }
        };
        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")))
                .build();
        adapter.getBluetoothLeScanner().startScan(
                List.of(filter), new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback);
        status.setText("Scanning for Meshtastic radios for 8 seconds…");
        status.postDelayed(() -> {
            try { adapter.getBluetoothLeScanner().stopScan(callback); } catch (Exception ignored) {}
            if (found.isEmpty()) {
                status.setText("No Meshtastic BLE advertisement found. Disconnect the radio from the official app and retry.");
                return;
            }
            List<String> addresses = new ArrayList<>(found.keySet());
            String[] labels = addresses.stream().map(found::get).toArray(String[]::new);
            new AlertDialog.Builder(this)
                    .setTitle("Select Meshtastic radio")
                    .setItems(labels, (dialog, which) -> {
                        select(transport, "ble");
                        bleAddress.setText(addresses.get(which));
                        updateTransportFields();
                        status.setText("Selected BLE radio " + labels[which] + ". Press Save & start to connect.");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }, 8_000);
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(BridgeService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED);
        else registerStatusReceiverLegacy(filter);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerStatusReceiverLegacy(IntentFilter filter) {
        // RECEIVER_NOT_EXPORTED does not exist before API 33. This broadcast is
        // explicitly package-scoped by BridgeService and carries status text only.
        registerReceiver(statusReceiver, filter);
    }

    @Override protected void onStop() {
        unregisterReceiver(statusReceiver);
        super.onStop();
    }

    private EditText text(String hint, boolean numeric) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        if (numeric) input.setInputType(InputType.TYPE_CLASS_NUMBER);
        return input;
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values));
        return spinner;
    }

    private void add(LinearLayout form, String label, View field) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setPadding(0, dp(9), 0, 0);
        form.addView(text);
        form.addView(field);
    }

    private static String value(EditText field) { return field.getText().toString().trim(); }
    private static int integer(EditText field) { return Integer.parseInt(value(field)); }
    private static String selected(Spinner field) { return field.getSelectedItem().toString(); }

    private void updateTransportFields() {
        if (transport == null || radioHost == null || radioPort == null || bleAddress == null || bleScan == null) return;
        boolean tcp = selected(transport).equals("tcp");
        radioHost.setEnabled(tcp);
        radioPort.setEnabled(tcp);
        bleAddress.setEnabled(!tcp);
        bleScan.setEnabled(!tcp);
        radioHost.setAlpha(tcp ? 1.0f : 0.45f);
        radioPort.setAlpha(tcp ? 1.0f : 0.45f);
        bleAddress.setAlpha(tcp ? 0.45f : 1.0f);
        bleScan.setAlpha(tcp ? 0.45f : 1.0f);
    }

    private void updateModeFields() {
        if (mode == null || gateway == null || allowedSources == null || ackPolicy == null) return;
        boolean broadcast = selected(mode).equals("broadcast");
        gateway.setEnabled(!broadcast);
        gateway.setAlpha(broadcast ? 0.45f : 1.0f);
        allowedSources.setEnabled(broadcast);
        allowedSources.setAlpha(broadcast ? 1.0f : 0.45f);
        ackPolicy.setEnabled(!broadcast);
        ackPolicy.setAlpha(broadcast ? 0.45f : 1.0f);
    }

    private String ackPolicyValue() {
        String value = selected(ackPolicy);
        return value.startsWith("all") ? "all" : value;
    }

    private static void select(Spinner spinner, String value) {
        for (int i = 0; i < spinner.getCount(); i++) {
            if (spinner.getItemAtPosition(i).toString().equals(value)) { spinner.setSelection(i); return; }
        }
    }

    private void openBackgroundSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + getPackageName())));
        } catch (Exception error) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
