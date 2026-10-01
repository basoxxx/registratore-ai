package it.registratoreai

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import it.registratoreai.service.CaptureService
import it.registratoreai.ui.AppNavigation
import it.registratoreai.ui.theme.RegistratoreTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** File audio condiviso da un'altra app, da importare. */
    val sharedAudio = mutableStateOf<Uri?>(null)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.launch(wanted.toTypedArray())

        // Riprende eventuali trascrizioni rimaste a metà (app chiusa, telefono riavviato…)
        lifecycleScope.launch {
            if (app.db.recordings().pendingTranscriptions().isNotEmpty()) {
                CaptureService.send(this@MainActivity, CaptureService.ACTION_RESUME_PENDING)
            }
        }

        setContent {
            RegistratoreTheme {
                AppNavigation(this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            sharedAudio.value = uri
        }
    }
}
