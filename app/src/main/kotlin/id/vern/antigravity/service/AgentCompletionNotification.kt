package id.vern.antigravity.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import id.vern.antigravity.R
import id.vern.antigravity.ui.MainActivity

object AgentCompletionNotification {

    private const val TAG = "AgentCompletionNotif"
    const val NOTIFICATION_ID = 1002
    const val CHANNEL_ID = "agy_agent_completion_channel"

    @Volatile
    private var isActivityInForeground: Boolean = false

    fun onActivityResumed(context: Context) {
        isActivityInForeground = true
        cancel(context)
    }

    fun onActivityPaused() {
        isActivityInForeground = false
    }

    fun cancel(context: Context) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    fun shouldNotify(context: Context): Boolean {
        if (!isActivityInForeground) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isScreenOn = try {
            powerManager?.isInteractive ?: true
        } catch (_: Exception) {
            true
        }
        return !isScreenOn
    }

    fun notifyTurnFinished(
        context: Context,
        conversationTitle: String?,
        responsePreview: String,
        durationText: String?,
        isError: Boolean = false
    ) {
        if (!shouldNotify(context)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.agent_done_notification_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.agent_done_notification_channel_desc)
                    enableLights(true)
                    enableVibration(true)
                    setShowBadge(true)
                }
                manager.createNotificationChannel(channel)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = when {
                isError -> context.getString(R.string.agent_error_notification_title)
                !durationText.isNullOrBlank() -> context.getString(R.string.agent_done_notification_title_duration, durationText)
                else -> context.getString(R.string.agent_done_notification_title)
            }

            val cleanedBody = cleanPreviewText(responsePreview)
                .ifBlank { context.getString(R.string.chat_task_completed) }
            val shortBody = if (cleanedBody.length > 140) cleanedBody.take(137).trimEnd() + "…" else cleanedBody
            val expandedBody = if (cleanedBody.length > 420) cleanedBody.take(417).trimEnd() + "…" else cleanedBody

            val iconRes = context.resources.getIdentifier("ic_notification", "drawable", context.packageName)
                .takeIf { it != 0 } ?: android.R.drawable.stat_notify_chat

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(iconRes)
                .setContentTitle(title)
                .setContentText(shortBody)
                .setStyle(NotificationCompat.BigTextStyle().bigText(expandedBody))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            val safeSubText = conversationTitle?.trim()
            if (!safeSubText.isNullOrBlank() && safeSubText != "New Conversation") {
                builder.setSubText(safeSubText.take(48))
            }

            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post agent completion notification: ${e.message}")
        }
    }

    private fun cleanPreviewText(raw: String): String {
        return raw
            .replace(Regex("```[\\s\\S]*?```"), "[code]")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("^[#>*-]+\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("[*_~]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
