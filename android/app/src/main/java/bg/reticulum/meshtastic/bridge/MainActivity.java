package bg.reticulum.meshtastic.bridge;

import android.Manifest;
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
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
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
    private EditText localPort;
    private EditText channel;
    private EditText hops;
    private Spinner mode;
    private EditText gateway;
    private EditText fragmentBody;
    private EditText txInterval;
    private CheckBox wantAck;
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
        explanation.setText("Local Reticulum TCP server for Sideband/Columba. The server is bound only to 127.0.0.1.");
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
        Button scan = new Button(this);
        scan.setText("Scan for Meshtastic BLE radios");
        scan.setOnClickListener(ignored -> scanBle());
        form.addView(scan);
        localPort = text("7822", true);
        add(form, "Local Reticulum TCP port", localPort);
        channel = text("0", true);
        add(form, "Meshtastic channel index (HQ = 0)", channel);
        hops = text("3", true);
        add(form, "Hop limit", hops);
        mode = spinner(new String[] {"gateway_unicast", "broadcast"});
        add(form, "Radio addressing mode", mode);
        gateway = text("!8fd13c64", false);
        add(form, "Linux gateway Meshtastic Node ID", gateway);
        fragmentBody = text("200", true);
        add(form, "Fragment payload bytes", fragmentBody);
        txInterval = text("2000", true);
        add(form, "Delay between LoRa fragments (ms)", txInterval);
        wantAck = new CheckBox(this);
        wantAck.setText("Request Meshtastic ACK for unicast fragments");
        form.addView(wantAck);

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

        status = new TextView(this);
        status.setText("Bridge is not running");
        status.setTextIsSelectable(true);
        status.setPadding(0, dp(16), 0, dp(24));
        form.addView(status);

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
        fragmentBody.setText(String.valueOf(config.fragmentBody));
        txInterval.setText(String.valueOf(config.txIntervalMillis));
        wantAck.setChecked(config.wantAck);
    }

    private void saveAndStart() {
        try {
            BridgeConfig config = new BridgeConfig(
                    selected(transport), value(radioHost), integer(radioPort), value(bleAddress),
                    integer(localPort), integer(channel), integer(hops), selected(mode), value(gateway),
                    integer(fragmentBody), integer(txInterval), wantAck.isChecked());
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
                    .setItems(labels, (dialog, which) -> bleAddress.setText(addresses.get(which)))
                    .setNegativeButton("Cancel", null)
                    .show();
        }, 8_000);
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(BridgeService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(statusReceiver, filter);
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

    private static void select(Spinner spinner, String value) {
        for (int i = 0; i < spinner.getCount(); i++) {
            if (spinner.getItemAtPosition(i).toString().equals(value)) { spinner.setSelection(i); return; }
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
