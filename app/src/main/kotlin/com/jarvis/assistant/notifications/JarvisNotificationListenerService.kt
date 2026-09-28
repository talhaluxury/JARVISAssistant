package com.jarvis.assistant.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.jarvis.assistant.hud.JarvisHudState
import com.jarvis.assistant.hud.WallpaperEventBus

data class JarvisNotification(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long
)

/** Explicitly user-enabled notification access. The service stores only a small in-memory
 * window; nothing is persisted unless the user asks JARVIS to remember something. */
class JarvisNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        refresh()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        _notifications.value = emptyList()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification) = refresh()

    private fun refresh() {
        val values = runCatching { activeNotifications?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .sortedByDescending { it.postTime }
            .take(100)
            .mapNotNull { sbn ->
                val extras = sbn.notification.extras ?: return@mapNotNull null
                JarvisNotification(
                    sbn.key, sbn.packageName,
                    extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                    extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                    sbn.postTime
                )
            }
        _notifications.value = values
        if (values.isNotEmpty()) WallpaperEventBus.emit(JarvisHudState.IDLE, "NOTIFICATIONS", "NOTIFICATIONS ${values.size}")
    }

    companion object {
        private var instance: JarvisNotificationListenerService? = null
        private val _notifications = MutableStateFlow<List<JarvisNotification>>(emptyList())
        val notifications: StateFlow<List<JarvisNotification>> = _notifications.asStateFlow()
        val isEnabled: Boolean get() = instance != null
        val activeNotificationCount: Int get() = _notifications.value.size

        fun summary(max: Int = 8): String {
            val list = notifications.value.take(max)
            if (list.isEmpty()) return "No active notifications available."
            return list.joinToString("\n") { n ->
                listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(": ")
            }
        }
    }
}
