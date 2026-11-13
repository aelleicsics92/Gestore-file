package it.dalbrocante.gestorefile.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import it.dalbrocante.gestorefile.util.Updater
import it.dalbrocante.gestorefile.wear.AdbDiscovery
import it.dalbrocante.gestorefile.wear.AdbInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Campo che seleziona tutto il contenuto al focus; Invio passa al campo successivo (o conferma). */
@Composable
private fun Field(value: String, onValue: (String) -> Unit, label: String, last: Boolean, number: Boolean, onDone: () -> Unit) {
    var tf by remember { mutableStateOf(TextFieldValue(value)) }
    if (tf.text != value) tf = TextFieldValue(value)
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = tf,
        onValueChange = { tf = it; onValue(it.text) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text, imeAction = if (last) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }, onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) tf = tf.copy(selection = TextRange(0, tf.text.length)) },
    )
}

/**
 * Installa APK sull'orologio via Debug Wi-Fi. Dopo la prima associazione basta un tocco:
 * l'orologio viene trovato da solo sulla rete (niente IP né porta). Se [apks] è dato, li installa subito.
 */
@Composable
fun WatchInstallDialog(apks: List<File>? = null, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("watch_adb", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var paired by remember { mutableStateOf(prefs.getBoolean("paired", false)) }
    var manual by remember { mutableStateOf(false) }
    var ip by remember { mutableStateOf(prefs.getString("ip", "") ?: "") }
    var port by remember { mutableStateOf("") }
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

    suspend fun endpoint(): Pair<String, Int> {
        status = "Cerco l'orologio sulla rete…"
        val found = AdbDiscovery.find(ctx, AdbDiscovery.CONNECT, 8000, prefs.getString("ip", null))
        if (found != null) { prefs.edit().putString("ip", found.first).apply(); return found }
        val p = port.trim().toIntOrNull()
        if (ip.trim().isNotEmpty() && p != null) return ip.trim() to p
        error("Orologio non trovato: attiva Debug Wi-Fi sull'orologio (stessa Wi-Fi) oppure usa IP e porte manuali")
    }

    suspend fun installFiles(files: List<File>) {
        val (h, p) = endpoint()
        files.forEachIndexed { i, f ->
            status = "Installo ${f.name} (${i + 1}/${files.size})…"
            AdbInstaller.install(ctx, h, p, f)
        }
        status = "Installato: " + files.joinToString { it.name }
    }

    fun pair() = run {
        status = "Cerco l'orologio in modalità associazione…"
        val manualEp = ip.trim().takeIf { it.isNotEmpty() }?.let { h -> pairPort.trim().toIntOrNull()?.let { h to it } }
        val ep = AdbDiscovery.find(ctx, AdbDiscovery.PAIRING, 8000, null) ?: manualEp
            ?: error("Sull'orologio apri \"Associa nuovo dispositivo\" e lascia la schermata aperta")
        status = "Associazione…"
        AdbInstaller.pair(ctx, ep.first, ep.second, code.trim())
        prefs.edit().putBoolean("paired", true).putString("ip", ep.first).apply()
        paired = true
        code = ""
        status = "Associato. D'ora in poi basta toccare Installa."
    }

    fun latest() = run {
        status = "Cerco l'ultima versione…"
        val info = withContext(Dispatchers.IO) { Updater.checkAsset("GestoreFileWatch.apk") } ?: error("Aggiornamento non trovato su GitHub")
        val f = withContext(Dispatchers.IO) { Updater.download(ctx, info.url, "GestoreFileWatch.apk") { p -> status = "Scarico build ${info.build}… $p%" } }
        installFiles(listOf(f))
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) run {
            val files = withContext(Dispatchers.IO) {
                uris.mapIndexed { i, u ->
                    File(ctx.cacheDir, "inst_$i.apk").also { f ->
                        (ctx.contentResolver.openInputStream(u) ?: error("File non leggibile")).use { input -> f.outputStream().use { input.copyTo(it) } }
                    }
                }
            }
            installFiles(files)
        }
    }

    LaunchedEffect(Unit) {
        if (apks != null) {
            if (paired) run { installFiles(apks) } else status = "Prima associa l'orologio (una volta sola)."
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Installa APK sull'orologio") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!paired) {
                    Text(
                        "Una volta sola: sull'orologio apri Impostazioni > Opzioni sviluppatore > Debug Wi-Fi > Associa nuovo dispositivo, lascia aperta la schermata e scrivi qui il codice.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Field(code, { code = it }, "Codice associazione", last = true, number = true, onDone = { pair() })
                } else {
                    Text("Orologio associato. Servono Debug Wi-Fi attivo sull'orologio e la stessa Wi-Fi del telefono.", style = MaterialTheme.typography.bodySmall)
                }
                TextButton({ manual = !manual }) { Text(if (manual) "Nascondi impostazioni manuali" else "Impostazioni manuali (IP e porte)") }
                if (manual) {
                    Field(ip, { ip = it }, "IP orologio", last = false, number = false, onDone = {})
                    Field(pairPort, { pairPort = it }, "Porta associazione", last = false, number = true, onDone = {})
                    Field(port, { port = it }, "Porta connessione", last = true, number = true, onDone = {})
                    if (paired) TextButton({ paired = false; prefs.edit().putBoolean("paired", false).apply() }) { Text("Associa di nuovo") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            if (!paired) Button({ pair() }, enabled = !busy) { Text("Associa") }
            else Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({ picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Scegli APK") }
                Button({ latest() }, enabled = !busy) { Text("Gestore file") }
            }
        },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("Chiudi") } },
    )
}
