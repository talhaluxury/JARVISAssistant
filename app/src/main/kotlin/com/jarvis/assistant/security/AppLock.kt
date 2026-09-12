package com.jarvis.assistant.security

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Lets the phone's owner set a PIN and hide JARVIS from the app drawer, so
 * someone else who picks up the phone can't find it or open it.
 *
 * "Hiding" disables a separate launcher-icon alias (see AndroidManifest.xml)
 * rather than the app itself, so the app keeps working normally and can be
 * reached again either from Settings > Apps, or via the secret dial code
 * handled by [SecretCodeReceiver].
 *
 * The PIN gate in AppLockScreen is independent of hiding: if a PIN is set,
 * it is required every time the app is opened, whether or not the icon is
 * currently visible.
 */
object AppLock {
    private const val PREFS = "jarvis_app_lock"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_SALT = "pin_salt"
    private const val KEY_HIDDEN = "hidden"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun launcherAlias(context: Context) =
        ComponentName(context.packageName, "${context.packageName}.MainLauncherAlias")

    fun isPinSet(context: Context): Boolean = prefs(context).contains(KEY_HASH)

    fun setPin(context: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(context).edit()
            .putString(KEY_SALT, salt.joinToString(",") { (it.toInt() and 0xFF).toString() })
            .putString(KEY_HASH, hash(pin, salt))
            .apply()
    }

    /** Removes the PIN entirely (also un-hides the app, since there'd be no way back in). */
    fun clearPinAndUnhide(context: Context) {
        prefs(context).edit().remove(KEY_HASH).remove(KEY_SALT).apply()
        setHidden(context, false)
    }

    fun verify(context: Context, pin: String): Boolean {
        val p = prefs(context)
        val saltStr = p.getString(KEY_SALT, null) ?: return false
        val storedHash = p.getString(KEY_HASH, null) ?: return false
        val salt = saltStr.split(",").map { it.toInt().toByte() }.toByteArray()
        return hash(pin, salt) == storedHash
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val bytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isHidden(context: Context): Boolean = prefs(context).getBoolean(KEY_HIDDEN, false)

    fun setHidden(context: Context, hidden: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDDEN, hidden).apply()
        val state = if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        try {
            context.packageManager.setComponentEnabledSetting(
                launcherAlias(context), state, PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {
            // If this ever fails, the app just stays visible - never silently locks the owner out.
        }
    }
}
