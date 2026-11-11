package it.dalbrocante.gestorefile.wear

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/** Voce "Invia all'orologio" nel menu Condividi di Android. */
class SendToWatchActivity : Activity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            else -> emptyList()
        }
        val items = WearBridge.resolve(this, uris)
        if (items.isEmpty()) Toast.makeText(this, "Niente da inviare", Toast.LENGTH_SHORT).show()
        else {
            WearBridge.enqueue(this, items, "")
            Toast.makeText(this, "Invio di ${items.size} file all'orologio", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
