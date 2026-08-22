/**
 * FirebaseMessagingService glue. Host apps either:
 *  (a) declare THIS service in their manifest (zero-code path), or
 *  (b) keep their own FirebaseMessagingService and forward both callbacks:
 *      onNewToken → Notibase.registerToken(token)
 *      onMessageReceived → NotibaseNotifications.display(this, msg.data, msg.notification?.title, msg.notification?.body)
 *
 * Rendering note: when the app is BACKGROUNDED and the payload carries a
 * `notification` block, Android's system tray renders it and this callback
 * never fires — that path needs no SDK code. This service covers the
 * foreground case and data-only messages.
 */
package com.notibase.sdk

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

public open class NotibaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Notibase.registerToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        NotibaseNotifications.display(
            context = this,
            data = message.data,
            title = message.notification?.title,
            body = message.notification?.body,
        )
    }
}
