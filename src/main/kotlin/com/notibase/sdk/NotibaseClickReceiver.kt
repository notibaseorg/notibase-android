/**
 * Click trampoline — notifications rendered by the SDK route their taps
 * through this receiver so opens are recorded automatically (the OneSignal
 * pattern), then the original destination opens: the click URL if the
 * payload had one, else the app's launcher activity.
 *
 * Declared in the SDK manifest (merged into hosts); exported=false so only
 * PendingIntents from this app can reach it.
 */
package com.notibase.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.notibase.sdk.internal.NotibaseApi

public class NotibaseClickReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val m = intent.getStringExtra(EXTRA_MESSAGE_ID)
        val d = intent.getStringExtra(EXTRA_DEVICE_ID)
        val o = intent.getStringExtra(EXTRA_ORIGIN)
        if (m != null && d != null && o != null) {
            Thread({ NotibaseApi.postClick(o, m, d) }, "notibase-click").apply {
                isDaemon = true
            }.start()
        }

        // An action tap should dismiss the notification the way a body tap does
        // (setAutoCancel only covers the content intent), otherwise the tray
        // keeps a notification the person has already acted on.
        intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0).takeIf { it != 0 }?.let { id ->
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager)
                ?.cancel(id)
        }

        val url = intent.getStringExtra(EXTRA_URL)
        val next = if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName)
        }
        next?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Which button was tapped, for the host app to branch on. Absent for a
        // plain body tap, so `null` genuinely means "the notification itself".
        intent.getStringExtra(EXTRA_ACTION_ID)?.let { next?.putExtra(EXTRA_ACTION_ID, it) }
        next?.let { context.startActivity(it) }
    }

    public companion object {
        internal const val EXTRA_MESSAGE_ID: String = "com.notibase.sdk.nb_m"
        internal const val EXTRA_DEVICE_ID: String = "com.notibase.sdk.nb_d"
        internal const val EXTRA_ORIGIN: String = "com.notibase.sdk.nb_o"
        internal const val EXTRA_URL: String = "com.notibase.sdk.url"
        internal const val EXTRA_NOTIFICATION_ID: String = "com.notibase.sdk.notification_id"

        /** Read this from your launch Intent to know which button was tapped. */
        public const val EXTRA_ACTION_ID: String = "com.notibase.sdk.action_id"
    }
}
