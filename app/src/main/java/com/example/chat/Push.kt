package com.example.chat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object Push {
    const val CH_MESSAGES = "messages"
    const val CH_CALLS = "calls"

    /** false, если в проект не добавлен google-services.json. */
    fun available(ctx: Context) = FirebaseApp.getApps(ctx).isNotEmpty()

    fun currentToken(ctx: Context, onToken: (String) -> Unit) {
        if (!available(ctx)) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { onToken(it) }
    }

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_MESSAGES, "Сообщения", NotificationManager.IMPORTANCE_HIGH)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_CALLS, "Звонки", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }
}

class PushService : FirebaseMessagingService() {

    /** FCM выдал новый токен: сообщаем серверу, если пользователь уже вошёл. */
    override fun onNewToken(token: String) {
        val prefs = getSharedPreferences("chat", MODE_PRIVATE)
        val session = prefs.getString("token", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                ApiClient().registerDevice(session, token)
                prefs.edit().putString("fcm", token).apply()
            }
        }
    }

    /** Сервер шлёт data-only сообщения, поэтому уведомление всегда строим сами. */
    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        val roomId = d["room_id"]?.toLongOrNull() ?: return
        val isCall = d["type"] == "call"
        // заглушение чата (см. ChatViewModel.setMuted) — только на этом устройстве, звонки не заглушает
        if (!isCall && getSharedPreferences("chat", MODE_PRIVATE).getBoolean("mute_$roomId", false)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        Push.ensureChannels(this)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("room_id", roomId)
        }
        val pending = PendingIntent.getActivity(
            this, roomId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = d["body"].orEmpty()
        val builder = NotificationCompat.Builder(this, if (isCall) Push.CH_CALLS else Push.CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(d["title"])
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
        if (isCall) {
            builder.setCategory(NotificationCompat.CATEGORY_CALL).setTimeoutAfter(45_000)
        }
        // один id на комнату: новые сообщения обновляют уведомление, а не множат их
        NotificationManagerCompat.from(this).notify(roomId.toInt(), builder.build())
    }
}
