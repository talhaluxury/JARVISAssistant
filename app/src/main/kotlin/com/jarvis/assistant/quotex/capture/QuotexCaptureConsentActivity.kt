package com.jarvis.assistant.quotex.capture

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.jarvis.assistant.quotex.overlay.QuotexOverlayService

/** Shows Android's own screen-capture consent dialog. Capture never starts without it. */
class QuotexCaptureConsentActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            QuotexMonitorService.start(this, result.resultCode, data)
            QuotexOverlayService.show(this)
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
