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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import com.notibase.sdk.internal.NotibaseButton
import com.notibase.sdk.internal.NotibaseButtons
import java.net.HttpURLConnection
import java.net.URL

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

        // Large icon — the square avatar/brand mark beside the text. FCM has no
        // field for it, so it rides in the data payload and we fetch it here.
        data["nb_large_icon"]?.let { url ->
            fetchBitmap(url)?.let { builder.setLargeIcon(it) }
        }

        // Action buttons. Android draws at most three and silently drops the
        // rest, so trim where it is visible rather than letting the platform
        // decide which of the customer's buttons disappear.
        NotibaseButtons.parse(data["nb_buttons"]).forEach { b ->
            val intent = actionIntent(context, data, b) ?: return@forEach
            if (Build.VERSION.SDK_INT >= 23) {
                builder.addAction(Notification.Action.Builder(null, b.text, intent).build())
            } else {
                @Suppress("DEPRECATION")
                builder.addAction(0, b.text, intent)
            }
        }

        manager.notify((data["nb_m"] ?: data["url"]).hashCode(), builder.build())
    }

    /**
     * Blocking, deliberately: onMessageReceived already runs off the main
     * thread and the system gives it ~10s, so a bounded fetch here is safe and
     * keeps the notification single-shot. A slow or broken image costs the icon,
     * never the notification.
     */
    private fun fetchBitmap(url: String): Bitmap? {
        if (!url.startsWith("https://")) return null   // cleartext icons: no
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun actionIntent(context: Context, data: Map<String, String>, b: NotibaseButton): PendingIntent? {
        val url = b.url ?: data["url"]
        val tramp = Intent(context, NotibaseClickReceiver::class.java).apply {
            putExtra(NotibaseClickReceiver.EXTRA_MESSAGE_ID, data["nb_m"])
            putExtra(NotibaseClickReceiver.EXTRA_DEVICE_ID, data["nb_d"])
            putExtra(NotibaseClickReceiver.EXTRA_ORIGIN, data["nb_o"])
            putExtra(NotibaseClickReceiver.EXTRA_ACTION_ID, b.id)
            putExtra(NotibaseClickReceiver.EXTRA_NOTIFICATION_ID, (data["nb_m"] ?: data["url"]).hashCode())
            if (url != null) putExtra(NotibaseClickReceiver.EXTRA_URL, url)
        }
        // Distinct request code per button, or PendingIntent reuse would give
        // every button the first button's extras — a classic and invisible bug.
        return PendingIntent.getBroadcast(
            context, (data["nb_m"] + ":" + b.id).hashCode(), tramp, pendingFlags()
        )
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
