package com.jarvis.assistant.wingo.capture

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.jarvis.assistant.wingo.overlay.WinGoOverlayService

/**
 * Transparent activity that shows Android's own screen-capture consent dialog. Capture never starts
 * without this explicit approval, and Android may ask again every time.
 */
class WinGoCaptureConsentActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            WinGoMonitorService.start(this, result.resultCode, data)
            WinGoOverlayService.show(this)
        } else {
            Toast.makeText(this, "Screen capture permission denied. Monitoring stays off.", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(manager.createScreenCaptureIntent())
    }
}
