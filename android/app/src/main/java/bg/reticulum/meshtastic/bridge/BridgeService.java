package bg.reticulum.meshtastic.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

public final class BridgeService extends Service {
    static final String ACTION_START = "bg.reticulum.meshtastic.bridge.START";
    static final String ACTION_STOP = "bg.reticulum.meshtastic.bridge.STOP";
    static final String ACTION_STATUS = "bg.reticulum.meshtastic.bridge.STATUS";
    static final String EXTRA_STATUS = "status";
    private static final String CHANNEL_ID = "bridge";
    private static final int NOTIFICATION_ID = 76;
    private BridgeEngine engine;
    private PowerManager.WakeLock wakeLock;

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
        startForeground(NOTIFICATION_ID, notification(getString(R.string.service_running)));
        if (engine == null) startBridge();
        else restartBridge();
        return START_STICKY;
    }

    private void restartBridge() {
        BridgeEngine current = engine;
        engine = null;
        if (current != null) current.close();
        releaseWakeLock();
        publishStatus("Applying new configuration…");
        startBridge();
    }

    private void startBridge() {
        try {
            BridgeConfig config = BridgeConfig.load(this);
            PowerManager power = getSystemService(PowerManager.class);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rns-meshtastic:bridge");
            wakeLock.acquire();
            engine = new BridgeEngine(this, config, this::publishStatus);
            engine.start();
            publishStatus("Bridge started");
        } catch (Exception error) {
            publishStatus("Bridge could not start: " + useful(error));
            stopSelf();
        }
    }

    private void publishStatus(String status) {
        Intent update = new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra(EXTRA_STATUS, status);
        sendBroadcast(update);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, notification(status.replace('\n', ' ')));
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
        BridgeEngine current = engine;
        engine = null;
        if (current != null) current.close();
        releaseWakeLock();
        publishStopped();
        super.onDestroy();
    }

    private void publishStopped() {
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra(EXTRA_STATUS, "Bridge stopped"));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private static String useful(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
