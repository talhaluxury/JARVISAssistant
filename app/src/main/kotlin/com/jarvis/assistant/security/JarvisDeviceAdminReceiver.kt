package com.jarvis.assistant.security

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Just by being an active Device Admin, Android disables the normal
 * "Uninstall" button for this app in Settings > Apps - the user has to go
 * to Settings > Security > Device admin apps and explicitly deactivate this
 * admin first, which shows [onDisableRequested]'s warning.
 *
 * This is the standard, OS-sanctioned level of uninstall protection normal
 * apps are allowed to use (the same mechanism anti-theft/parental-control
 * apps use). It is a real deterrent, not an absolute guarantee - Android
 * intentionally does not let any ordinary app inject its own PIN prompt
 * into the system's own deactivation screen, since that would be exactly
 * the technique malicious/uninstall-resistant apps use.
 */
class JarvisDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Disabling this lets JARVIS be uninstalled. Only do this if you " +
            "are the phone's owner and want to remove the app."
    }
}
