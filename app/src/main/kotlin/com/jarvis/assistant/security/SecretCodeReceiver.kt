package com.jarvis.assistant.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.MainActivity

/**
 * Re-shows JARVIS's launcher icon and opens it when the owner dials the
 * secret code registered in AndroidManifest.xml (currently *#*#1231#*#*)
 * in the phone's own Dialer app.
 *
 * Support for "secret codes" varies by phone/dialer app - most stock
 * Android and Samsung dialers support it, some others don't. If it doesn't
 * work on this phone, the app can still be found and re-enabled from
 * Android Settings > Apps > JARVIS (it is never actually uninstalled or
 * disabled as a whole - only its launcher icon is hidden).
 *
 * Opening the app this way still requires entering the correct PIN
 * (see AppLockScreen) - dialing the code alone does not bypass the lock.
 */
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SECRET_CODE") return
        AppLock.setHidden(context, false)
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
    }
}
