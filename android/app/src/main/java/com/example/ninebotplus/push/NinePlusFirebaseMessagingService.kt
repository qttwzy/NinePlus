package com.example.ninebotplus.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM entry point. NinePlus Platform should send Android data/notification
 * messages via FCM alongside APNs for iOS.
 *
 * When google-services.json is absent, FirebaseInitProvider is disabled and
 * this service simply never receives messages — the app still works.
 */
class NinePlusFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val app = application as? com.example.ninebotplus.NinePlusApp ?: return
        app.pushManager.onNewFcmToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val app = application as? com.example.ninebotplus.NinePlusApp ?: return
        val title = message.notification?.title ?: message.data["title"]
        val body = message.notification?.body ?: message.data["body"]
        val vehicleSn = message.data["vehicle_sn"]
        app.pushManager.onPushMessage(title, body, vehicleSn)
    }
}
