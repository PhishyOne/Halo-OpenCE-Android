package com.halo.decomp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/** Owns only the current game's CPU/Wi-Fi leases. Never restarts a dead game. */
public final class NetworkSessionService extends Service {
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("halo-session",
                "Multiplayer session", NotificationManager.IMPORTANCE_LOW));
            Intent open = new Intent(this, HaloActivity.class)
                .setAction("com.halo.decomp.RESUME_SESSION")
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            PendingIntent resume = PendingIntent.getActivity(this, 44, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, "halo-session")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Halo multiplayer is running")
                .setContentText("Keeping the session active with the screen off")
                .setContentIntent(resume).setOngoing(true).build();
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(44, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(44, notification);
            if (cpu == null) {
                cpu = getSystemService(PowerManager.class).newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "Halo:network-session");
                cpu.setReferenceCounted(false);
                cpu.acquire();
            }
            if (wifi == null) {
                WifiManager managerWifi = (WifiManager)getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
                if (managerWifi != null) {
                    wifi = managerWifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "Halo:network-session");
                    wifi.setReferenceCounted(false);
                    wifi.acquire();
                }
            }
        } catch (RuntimeException failure) {
            Log.e("Halo", "Background session protection failed", failure);
            String warning = "Background protection failed. Keep the screen on during multiplayer.";
            if (!HaloNotice.showFromService(warning)) {
                // No visible game window: use the OS notification surface,
                // rather than attempting a background Activity or toast.
                try {
                    NotificationManager manager = getSystemService(NotificationManager.class);
                    manager.createNotificationChannel(new NotificationChannel("halo-session-warning",
                        "Multiplayer warnings", NotificationManager.IMPORTANCE_DEFAULT));
                    manager.notify(45, new Notification.Builder(this, "halo-session-warning")
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setContentTitle("Halo multiplayer")
                        .setContentText(warning).setStyle(new Notification.BigTextStyle().bigText(warning))
                        .setAutoCancel(true).build());
                } catch (RuntimeException unavailable) {
                    Log.w("Halo", "Could not display multiplayer warning", unavailable);
                }
            }
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) { stopSelf(); }

    @Override public void onDestroy() {
        if (wifi != null && wifi.isHeld()) wifi.release();
        if (cpu != null && cpu.isHeld()) cpu.release();
        wifi = null; cpu = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
