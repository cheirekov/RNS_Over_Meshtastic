package bg.reticulum.meshtastic.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

public final class BridgeService extends Service {
    static final String ACTION_START = "bg.reticulum.meshtastic.bridge.START";
    static final String ACTION_STOP = "bg.reticulum.meshtastic.bridge.STOP";
    static final String ACTION_STATUS = "bg.reticulum.meshtastic.bridge.STATUS";
    static final String EXTRA_STATUS = "status";
    static final String LAST_STATUS = "last_status";
    private static final String CHANNEL_ID = "bridge";
    private static final int NOTIFICATION_ID = 76;
    private static final long STATUS_UPDATE_INTERVAL_MILLIS = 2_000;
    private static final long STATUS_PERSIST_INTERVAL_MILLIS = 30_000;
    private static volatile String processStatus;
    private final Object statusLock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());
    private BridgeEngine engine;
    private String pendingStatus;
    private String lastDeliveredStatus;
    private boolean statusScheduled;
    private boolean destroyed;
    private long lastStatusUpdate;
    private long lastStatusPersist;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        promoteToForeground();
        if (engine == null) startBridge();
        else if (intent != null && ACTION_START.equals(intent.getAction())) restartBridge();
        return START_STICKY;
    }

    private void promoteToForeground() {
        Notification running = notification(getString(R.string.service_running));
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, running, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, running);
        }
    }

    private void restartBridge() {
        BridgeEngine current = engine;
        engine = null;
        if (current != null) current.close();
        publishStatus("Applying new configuration…");
        startBridge();
    }

    private void startBridge() {
        try {
            BridgeConfig config = BridgeConfig.load(this);
            engine = new BridgeEngine(this, config, this::publishStatus);
            engine.start();
        } catch (Exception error) {
            publishStatus("Bridge could not start: " + useful(error));
            stopSelf();
        }
    }

    private void publishStatus(String status) {
        processStatus = status;
        synchronized (statusLock) {
            if (destroyed) return;
            pendingStatus = status;
            if (statusScheduled) return;
            long elapsed = SystemClock.elapsedRealtime();
            long delay = Math.max(0, lastStatusUpdate + STATUS_UPDATE_INTERVAL_MILLIS - elapsed);
            statusScheduled = true;
            main.postDelayed(this::flushStatus, delay);
        }
    }

    private void flushStatus() {
        String status;
        synchronized (statusLock) {
            statusScheduled = false;
            if (destroyed || pendingStatus == null) return;
            status = pendingStatus;
            pendingStatus = null;
            lastStatusUpdate = SystemClock.elapsedRealtime();
        }
        if (status.equals(lastDeliveredStatus)) return;
        lastDeliveredStatus = status;
        Intent update = new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra(EXTRA_STATUS, status);
        sendBroadcast(update);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, notification(status.replace('\n', ' ')));
        long elapsed = SystemClock.elapsedRealtime();
        if (elapsed - lastStatusPersist >= STATUS_PERSIST_INTERVAL_MILLIS) {
            getSharedPreferences(BridgeConfig.PREFS, MODE_PRIVATE).edit().putString(LAST_STATUS, status).apply();
            lastStatusPersist = elapsed;
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, BridgeService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text.length() > 120 ? text.substring(0, 120) : text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openIntent)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stopIntent).build())
                .build();
    }

    @Override public void onDestroy() {
        synchronized (statusLock) {
            destroyed = true;
            pendingStatus = null;
            statusScheduled = false;
        }
        main.removeCallbacksAndMessages(null);
        BridgeEngine current = engine;
        engine = null;
        if (current != null) current.close();
        publishStopped();
        super.onDestroy();
    }

    private void publishStopped() {
        String stopped = "Bridge stopped";
        processStatus = stopped;
        getSharedPreferences(BridgeConfig.PREFS, MODE_PRIVATE).edit().putString(LAST_STATUS, stopped).apply();
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra(EXTRA_STATUS, stopped));
    }

    static String latestStatus(Context context) {
        String current = processStatus;
        if (current != null) return current;
        return context.getSharedPreferences(BridgeConfig.PREFS, MODE_PRIVATE)
                .getString(LAST_STATUS, "Bridge is not running");
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static String useful(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
