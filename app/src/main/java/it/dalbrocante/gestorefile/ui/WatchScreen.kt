package it.dalbrocante.gestorefile.ui

import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.dalbrocante.gestorefile.shared.Entry
import it.dalbrocante.gestorefile.shared.WatchInfo
import it.dalbrocante.gestorefile.util.formatDate
import it.dalbrocante.gestorefile.util.formatSize
import it.dalbrocante.gestorefile.wear.WearBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private sealed interface WDlg {
    data object NewFolder : WDlg
    data class Rename(val e: Entry) : WDlg
    data object Delete : WDlg
}

/** Gestione dei file dell'orologio dal telefono: sfoglia, copia, sposta, elimina, rinomina, invia e scarica. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WatchScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val transfer by WearBridge.transfer.collectAsState()

    var info by remember { mutableStateOf<WatchInfo?>(null) }
    var path by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var free by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var hidden by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf<Set<String>>(emptySet()) }
    var clip by remember { mutableStateOf<List<String>>(emptyList()) }
    var clipCut by remember { mutableStateOf(false) }
    var dlg by remember { mutableStateOf<WDlg?>(null) }

    fun load(p: String = path) {
        scope.launch {
            loading = true
            val r = WearBridge.list(ctx, p, hidden)
            loading = false
            if (r.error != null) { error = r.error; entries = emptyList() }
            else { error = null; path = r.path; parent = r.parent; entries = r.entries; free = r.free }
            if (info == null && r.error == null) info = runCatching { WearBridge.info(ctx) }.getOrNull()
        }
    }

    fun say(msg: String) { scope.launch { snack.showSnackbar(msg) } }

    LaunchedEffect(Unit) { load("") }
    // ricarica l'elenco quando termina un trasferimento
    LaunchedEffect(transfer.tick) { if (transfer.tick > 0) { delay(400); load() } }

    BackHandler(sel.isNotEmpty() || parent != null) {
        if (sel.isNotEmpty()) sel = emptySet() else parent?.let { sel = emptySet(); load(it) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val items = WearBridge.resolve(ctx, uris)
        if (items.isNotEmpty()) { WearBridge.enqueue(ctx, items, path); say("Invio di ${items.size} file all'orologio") }
    }

    fun download() {
        val dst = File(Environment.getExternalStorageDirectory(), "Download/Orologio").absolutePath
        val paths = sel.toList()
        sel = emptySet()
        scope.launch {
            val n = WearBridge.pull(ctx, paths, dst)
            if (n < 0) say("Scaricamento non riuscito")
            else {
                WearBridge.update { it.copy(active = false, finished = false, error = null, done = 0, total = n, label = "Download/Orologio") }
                say("$n file in arrivo in Download/Orologio")
            }
        }
    }

    fun runOp(op: String, paths: List<String> = emptyList(), dst: String = "", name: String = "", ok: String = "Fatto") {
        scope.launch {
            val e = WearBridge.op(ctx, op, paths, dst, name)
            say(e ?: ok)
            sel = emptySet()
            load()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (sel.isEmpty()) "Orologio" else "${sel.size} selezionati", maxLines = 1)
                        if (sel.isEmpty() && info != null) Text(
                            "Liberi ${formatSize(free)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton({ if (sel.isNotEmpty()) sel = emptySet() else onBack() }) {
                        Icon(if (sel.isNotEmpty()) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack, "Indietro")
                    }
                },
                actions = {
                    if (sel.isEmpty()) {
                        IconButton({ picker.launch("*/*") }) { Icon(Icons.Filled.Upload, "Invia file qui") }
                        IconButton({ dlg = WDlg.NewFolder }) { Icon(Icons.Filled.CreateNewFolder, "Nuova cartella") }
                        IconButton({ hidden = !hidden; load() }) { Icon(if (hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "File nascosti") }
                        IconButton({ load() }) { Icon(Icons.Filled.Refresh, "Aggiorna") }
                    } else {
                        IconButton({ download() }) { Icon(Icons.Filled.Download, "Scarica sul telefono") }
                        IconButton({ clip = sel.toList(); clipCut = false; sel = emptySet(); say("Copiati ${clip.size}") }) { Icon(Icons.Filled.ContentCopy, "Copia") }
                        IconButton({ clip = sel.toList(); clipCut = true; sel = emptySet(); say("Pronti da spostare ${clip.size}") }) { Icon(Icons.Filled.DriveFileMove, "Sposta") }
                        if (sel.size == 1) IconButton({ dlg = WDlg.Rename(entries.first { it.path == sel.first() }) }) { Icon(Icons.Filled.DriveFileRenameOutline, "Rinomina") }
                        IconButton({ dlg = WDlg.Delete }) { Icon(Icons.Filled.Delete, "Elimina", tint = MaterialTheme.colorScheme.error) }
                    }
                },
            )
        },
        bottomBar = {
            if (clip.isNotEmpty() && sel.isEmpty()) {
                Surface(tonalElevation = 6.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${clip.size} da ${if (clipCut) "spostare" else "copiare"}", Modifier.weight(1f))
                        TextButton({ clip = emptyList() }) { Text("Annulla") }
                        Button({
                            val c = clip; val cut = clipCut; clip = emptyList()
                            runOp(if (cut) "move" else "copy", c, dst = path)
                        }) { Text("Incolla qui") }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (transfer.active || transfer.total > 0 || transfer.error != null) {
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp),
                    color = if (transfer.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            transfer.error ?: (if (transfer.total > 0) "${transfer.done}/${transfer.total}  ${transfer.label}" else transfer.label),
                            maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                        )
                        if (transfer.error == null && transfer.total > 0) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(progress = { (transfer.done.toFloat() / transfer.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                path.ifEmpty { "…" }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.WatchOff, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Button({ load("") }) { Text("Riprova") }
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (parent != null) item("up") {
                    ListItem(
                        headlineContent = { Text("Cartella superiore") },
                        leadingContent = { Icon(Icons.Filled.ArrowUpward, null) },
                        modifier = Modifier.combinedClickable { sel = emptySet(); load(parent!!) },
                    )
                }
                items(entries, key = { it.path }) { e ->
                    val selected = e.path in sel
                    ListItem(
                        headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(if (e.isDir) "Cartella" else "${formatSize(e.size)}  ·  ${formatDate(e.modified)}") },
                        leadingContent = {
                            if (selected) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            else Icon(if (e.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile, null)
                        },
                        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                if (sel.isNotEmpty() || !e.isDir) sel = if (selected) sel - e.path else sel + e.path
                                else load(e.path)
                            },
                            onLongClick = { sel = if (selected) sel - e.path else sel + e.path },
                        ),
                    )
                }
                if (entries.isEmpty() && !loading && error == null) item("empty") {
                    Text("Cartella vuota", Modifier.fillMaxWidth().padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    when (val d = dlg) {
        WDlg.NewFolder -> InputDialog("Nuova cartella", "Nome", onDismiss = { dlg = null }) { n -> dlg = null; runOp("mkdir", dst = path, name = n) }
        is WDlg.Rename -> InputDialog("Rinomina", "Nuovo nome", initial = d.e.name, onDismiss = { dlg = null }) { n -> dlg = null; runOp("rename", listOf(d.e.path), name = n) }
        WDlg.Delete -> ConfirmDialog("Elimina", "Eliminare ${sel.size} elementi dall'orologio? Operazione irreversibile.", { dlg = null }) {
            dlg = null; runOp("delete", sel.toList())
        }
        null -> Unit
    }
}
