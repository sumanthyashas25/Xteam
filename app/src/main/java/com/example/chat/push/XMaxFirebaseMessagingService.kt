package com.example.chat.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AgencyRepository
import com.example.data.FirebaseStartup
import com.example.domain.CatalogueSeedData
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

object PushTokenRegistry {
    private const val TAG = "XMaxPushRegistry"
    const val EXTRA_CHAT_ID = "extra_chat_id"
    const val EXTRA_BUSINESS_KIND = "extra_business_kind"
    const val EXTRA_BUSINESS_ID = "extra_business_id"
    const val CHANNEL_CHAT_ID = "xmax_chat_messages"
    const val CHANNEL_URGENT_ID = "xmax_chat_urgent"

    suspend fun syncCurrentToken(context: Context, companyId: String = CatalogueSeedData.DEFAULT_COMPANY_ID) {
        FirebaseStartup.ensureInitialized(context)
        val uid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull() ?: return
        val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return
        registerToken(context, companyId, uid, token)
    }

    private fun getFirestore(context: Context): FirebaseFirestore {
        val dbId = runCatching { context.getString(R.string.firestore_database_id) }.getOrNull()?.trim()
            ?.takeIf { it.isNotBlank() } ?: AgencyRepository.TARGET_FIRESTORE_DATABASE_ID
        return FirebaseFirestore.getInstance(dbId)
    }

    suspend fun registerToken(
        context: Context,
        companyId: String,
        uid: String,
        token: String
    ) {
        if (uid.isBlank() || token.isBlank()) return
        runCatching {
            val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.replace(Regex("[^A-Za-z0-9_-]"), "_")
                ?.take(48)
                .orEmpty()
                .ifBlank { "tok_" + abs(token.hashCode()) }
            val db = getFirestore(context)
            db.collection("companies").document(companyId)
                .collection("deviceTokens").document(uid)
                .collection("tokens").document(deviceId)
                .set(
                    mapOf(
                        "uid" to uid,
                        "token" to token,
                        "platform" to "android",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
        }.onFailure { err ->
            Log.d(TAG, "FCM token registration deferred: ${err.message}")
        }
    }

    suspend fun unregisterCurrentToken(context: Context, companyId: String = CatalogueSeedData.DEFAULT_COMPANY_ID) {
        val uid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull() ?: return
        runCatching {
            val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.replace(Regex("[^A-Za-z0-9_-]"), "_")
                ?.take(48)
                .orEmpty()
            if (deviceId.isNotBlank()) {
                val db = getFirestore(context)
                db.collection("companies").document(companyId)
                    .collection("deviceTokens").document(uid)
                    .collection("tokens").document(deviceId)
                    .delete().await()
            }
        }
    }
}

class XMaxFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val uid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull() ?: return
        serviceScope.launch {
            PushTokenRegistry.registerToken(applicationContext, CatalogueSeedData.DEFAULT_COMPANY_ID, uid, token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val title = message.notification?.title ?: data["title"].orEmpty().ifBlank { "X.MAX Update" }
        val body = message.notification?.body ?: data["body"].orEmpty().ifBlank { "You have a new internal update." }
        val chatId = data["chatId"].orEmpty()
        val businessKind = data["businessKind"].orEmpty()
        val businessId = data["businessId"].orEmpty()
        val urgent = data["priority"].equals("URGENT", ignoreCase = true)

        showChatNotification(title, body, chatId, businessKind, businessId, urgent)
    }

    private fun showChatNotification(
        title: String,
        body: String,
        chatId: String,
        businessKind: String,
        businessId: String,
        urgent: Boolean
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channelId = if (urgent) PushTokenRegistry.CHANNEL_URGENT_ID else PushTokenRegistry.CHANNEL_CHAT_ID

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = manager.getNotificationChannel(channelId)
            if (existing == null) {
                val ch = NotificationChannel(
                    channelId,
                    if (urgent) "Urgent Announcements & Approvals" else "Internal Chat Messages",
                    if (urgent) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
                )
                manager.createNotificationChannel(ch)
            }
        }

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (chatId.isNotBlank()) putExtra(PushTokenRegistry.EXTRA_CHAT_ID, chatId)
            if (businessKind.isNotBlank()) putExtra(PushTokenRegistry.EXTRA_BUSINESS_KIND, businessKind)
            if (businessId.isNotBlank()) putExtra(PushTokenRegistry.EXTRA_BUSINESS_ID, businessId)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            abs((chatId + businessId).hashCode()),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_xteam)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            val notifId = if (chatId.isNotBlank()) abs(chatId.hashCode()) else (System.currentTimeMillis() and 0xFFFFFF).toInt()
            manager.notify(notifId, notif)
        }
    }
}
