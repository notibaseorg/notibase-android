/**
 * Notification rendering — platform APIs only (no androidx dependency, by
 * design: the SDK must never force a NotificationCompat version on hosts).
 * NotificationChannel is created lazily on Android 8+ (API 26).
 */
package com.notibase.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

public object NotibaseNotifications {

    /**
     * Render a notification from a Notibase FCM payload.
     * [data] may carry "url" (deep link) plus custom keys; [title]/[body]
     * come from the notification block when present, else from data.
     */
    @JvmStatic
    public fun display(context: Context, data: Map<String, String>, title: String?, body: String?) {
        val shownTitle = title ?: data["title"] ?: return  // nothing to show
        val shownBody = body ?: data["body"]
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val cfg = Notibase.config

        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    cfg.notificationChannelId,
                    cfg.notificationChannelName,
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, cfg.notificationChannelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder.setContentTitle(shownTitle)
        if (shownBody != null) builder.setContentText(shownBody)
        builder.setSmallIcon(
            if (cfg.smallIconRes != 0) cfg.smallIconRes else context.applicationInfo.icon
        )
        builder.setAutoCancel(true)

        contentIntent(context, data)?.let { builder.setContentIntent(it) }

        manager.notify((data["nb_m"] ?: data["url"]).hashCode(), builder.build())
    }

    private fun pendingFlags(): Int = if (Build.VERSION.SDK_INT >= 23) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }

    private fun contentIntent(context: Context, data: Map<String, String>): PendingIntent? {
        val url = data["url"]
        // Click tracking (Arch §7.2): route the tap through the trampoline
        // receiver so opens are recorded, then continue to the destination.
        if (data["nb_m"] != null && data["nb_d"] != null && data["nb_o"] != null) {
            val tramp = Intent(context, NotibaseClickReceiver::class.java).apply {
                putExtra(NotibaseClickReceiver.EXTRA_MESSAGE_ID, data["nb_m"])
                putExtra(NotibaseClickReceiver.EXTRA_DEVICE_ID, data["nb_d"])
                putExtra(NotibaseClickReceiver.EXTRA_ORIGIN, data["nb_o"])
                if (url != null) putExtra(NotibaseClickReceiver.EXTRA_URL, url)
            }
            return PendingIntent.getBroadcast(
                context, data["nb_m"].hashCode(), tramp, pendingFlags()
            )
        }
        val intent = if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 0, intent, pendingFlags())
    }
}
