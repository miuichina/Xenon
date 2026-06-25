/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.telegram.ui.LaunchActivity;

public class NotificationsService extends Service {

    private static final String CHANNEL_ID = "xenon_push_service";
    private static final int NOTIFICATION_ID = 31337;

    @Override
    public void onCreate() {
        super.onCreate();
        ApplicationLoader.postInitApplication();
        if (zxc.iconic.xenon.NekoConfig.enablePushService) {
            startForegroundInternal();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (zxc.iconic.xenon.NekoConfig.enablePushService) {
            // Keep the app alive in the foreground with a non-dismissible notification,
            // so it keeps receiving updates (and capturing deleted messages) in the background.
            startForegroundInternal();
            return START_STICKY;
        }
        stopSelf();
        return START_NOT_STICKY;
    }

    private void startForegroundInternal() {
        ensureChannel();
        Intent launchIntent = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);
        launchIntent.setAction(Intent.ACTION_MAIN);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(":3")
                .setContentText(LocaleController.getString(R.string.PushServiceText))
                .setSmallIcon(R.drawable.notification)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .setColor(0xff000000)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable ignore) {
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, LocaleController.getString(R.string.PushServiceText), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(LocaleController.getString(R.string.PushServiceText));
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        channel.setShowBadge(false);
        try {
            nm.createNotificationChannel(channel);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onDestroy() {
        super.onDestroy();
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.getBoolean("pushService", true)) {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }
    }
}
