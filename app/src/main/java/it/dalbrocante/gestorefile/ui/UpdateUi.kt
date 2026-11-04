package it.dalbrocante.gestorefile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import it.dalbrocante.gestorefile.util.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UpdateDialog(info: Updater.Info, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Int?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aggiornamento disponibile") },
        text = {
            Column {
                Text("Build ${info.build} (installata: ${Updater.currentBuild()}). Si installa sopra quella attuale, senza perdere i dati.")
                progress?.let { LinearProgressIndicator(progress = { it / 100f }) }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton({
                scope.launch {
                    progress = 0; err = null
                    val r = runCatching { withContext(Dispatchers.IO) { Updater.download(ctx, info.url) { progress = it } } }
                    progress = null
                    r.onSuccess { if (Updater.install(ctx, it)) onDismiss() }.onFailure { err = it.message }
                }
            }, enabled = progress == null) { Text("Aggiorna") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Più tardi") } },
    )
}
