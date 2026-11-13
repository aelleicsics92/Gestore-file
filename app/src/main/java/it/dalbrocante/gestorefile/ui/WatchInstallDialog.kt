package it.dalbrocante.gestorefile.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import it.dalbrocante.gestorefile.util.Updater
import it.dalbrocante.gestorefile.wear.AdbInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Campo che seleziona tutto il contenuto quando prende il focus; Invio passa al campo successivo (o conferma). */
@Composable
private fun Field(value: String, onValue: (String) -> Unit, label: String, last: Boolean, number: Boolean, onDone: () -> Unit) {
    var tf by remember { mutableStateOf(TextFieldValue(value)) }
    if (tf.text != value) tf = TextFieldValue(value)
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    OutlinedTextField(
        value = tf,
        onValueChange = { tf = it; onValue(it.text) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text, imeAction = if (last) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }, onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) tf = tf.copy(selection = TextRange(0, tf.text.length)) },
    )
}

@Composable
fun WatchInstallDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("watch_adb", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var ip by remember { mutableStateOf(prefs.getString("ip", "") ?: "") }
    var port by remember { mutableStateOf(prefs.getString("port", "") ?: "") }
    var pairPort by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() } catch (e: Throwable) { status = "Errore: ${e.message ?: e.javaClass.simpleName}" } finally { busy = false }
        }
    }

    fun pair() = run {
        status = "Associazione…"
        AdbInstaller.pair(ctx, ip.trim(), pairPort.trim().toInt(), code.trim())
        prefs.edit().putString("ip", ip.trim()).apply()
        status = "Associato. Ora inserisci la porta di connessione e installa."
    }

    fun install() = run {
        prefs.edit().putString("ip", ip.trim()).putString("port", port.trim()).apply()
        status = "Cerco l'ultima versione…"
        val info = withContext(Dispatchers.IO) { Updater.checkAsset("GestoreFileWatch.apk") } ?: error("Aggiornamento non trovato su GitHub")
        status = "Scarico build ${info.build}…"
        val f = withContext(Dispatchers.IO) { Updater.download(ctx, info.url, "GestoreFileWatch.apk") { p -> status = "Scarico build ${info.build}… $p%" } }
        status = "Installo sull'orologio…"
        AdbInstaller.install(ctx, ip.trim(), port.trim().toInt(), f)
        status = "Installato (build ${info.build})"
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Installa app orologio") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Sull'orologio: Impostazioni > Opzioni sviluppatore > Debug Wi-Fi. Telefono e orologio sulla stessa Wi-Fi.\n" +
                        "1) Associa nuovo dispositivo: inserisci IP, porta e codice mostrati.\n" +
                        "2) Poi inserisci la porta di connessione (schermata principale del Debug Wi-Fi) e installa.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Field(ip, { ip = it }, "IP orologio", last = false, number = false, onDone = {})
                Field(pairPort, { pairPort = it }, "Porta associazione", last = false, number = true, onDone = {})
                Field(code, { code = it }, "Codice associazione", last = true, number = true, onDone = { pair() })
                Field(port, { port = it }, "Porta connessione", last = true, number = true, onDone = { install() })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({ pair() }, enabled = !busy) { Text("Associa") }
                Button({ install() }, enabled = !busy) { Text("Installa") }
            }
        },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("Chiudi") } },
    )
}
