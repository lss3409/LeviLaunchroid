package org.levimc.launcher.util;

import android.content.Context;

/**
 * v532：存档备份结果通知栏提醒（替代原备份完成弹窗）。
 * 用户要求：备份提示进通知栏，删掉弹窗。
 */
public final class BackupNotifier {

    private static final String CHANNEL_ID = "instance_backup";

    private BackupNotifier() {
    }

    public static void show(Context ctx, String title, String text) {
        try {
            android.app.NotificationManager nm =
                    ctx.getSystemService(android.app.NotificationManager.class);
            if (nm == null) {
                return;
            }
            android.app.Notification.Builder builder;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        CHANNEL_ID,
                        ctx.getString(org.levimc.launcher.R.string.backup_notif_channel),
                        android.app.NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(channel);
                builder = new android.app.Notification.Builder(ctx, CHANNEL_ID);
            } else {
                builder = new android.app.Notification.Builder(ctx);
            }
            builder.setSmallIcon(org.levimc.launcher.R.drawable.ic_leaf_logo_mono)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true);
            nm.notify(title == null ? 0xBA62 : title.hashCode(), builder.build());
        } catch (Throwable ignored) {
        }
    }
}
