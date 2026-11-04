package it.dalbrocante.gestorefile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import it.dalbrocante.gestorefile.data.LinkServer
import it.dalbrocante.gestorefile.data.OcrIndex
import it.dalbrocante.gestorefile.data.Shell
import it.dalbrocante.gestorefile.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Page2(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
            actions = actions,
        )
    }) { pad -> Box(Modifier.padding(pad).fillMaxSize()) { content() } }
}

// ---------- Doppio pannello ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Pane(
    dir: File, tick: Int, selected: Set<File>, active: Boolean, modifier: Modifier,
    onDir: (File) -> Unit, onSel: (Set<File>) -> Unit, onFocus: () -> Unit, vm: FileViewModel, onOpenFile: (File) -> Unit,
) {
    val items = remember(dir, tick) { vm.repo.list(dir) }
    Column(modifier.clickable { onFocus() }) {
        Surface(color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
            Text(dir.absolutePath, Modifier.fillMaxWidth().padding(6.dp), maxLines = 1, style = MaterialTheme.typography.labelSmall)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (dir != vm.root && dir.parentFile != null) {
                item { ListItem(headlineContent = { Text("..") }, modifier = Modifier.clickable { onFocus(); onDir(dir.parentFile!!) }) }
            }
            items(items, key = { it.file.absolutePath }) { it ->
                val sel = it.file in selected
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = if (sel) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                    leadingContent = { Icon(if (it.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile, null) },
                    headlineContent = { Text(it.name, maxLines = 1) },
                    supportingContent = { if (!it.isDir) Text(formatSize(it.size)) },
                    modifier = Modifier
                        .semantics { contentDescription = (if (it.isDir) "Cartella " else "File ") + it.name + (if (sel) ", selezionato" else "") }
                        .combinedClickable(
                            onClick = {
                                onFocus()
                                if (selected.isNotEmpty()) onSel(if (sel) selected - it.file else selected + it.file)
                                else if (it.isDir) onDir(it.file) else onOpenFile(it.file)
                            },
                            onLongClick = { onFocus(); onSel(if (sel) selected - it.file else selected + it.file) },
                        ),
                )
            }
        }
    }
}

@Composable
fun DualPaneScreen(vm: FileViewModel, onOpenFile: (File) -> Unit, onBack: () -> Unit) {
    var left by remember { mutableStateOf(vm.currentDir) }
    var right by remember { mutableStateOf(vm.root) }
    var selL by remember { mutableStateOf(emptySet<File>()) }
    var selR by remember { mutableStateOf(emptySet<File>()) }
    var active by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun transfer(move: Boolean) {
        val src = if (active == 0) selL else selR
        val dest = if (active == 0) right else left
        scope.launch {
            val e = withContext(Dispatchers.IO) {
                src.mapNotNull { f -> (if (move) vm.repo.move(f, dest) else vm.repo.copy(f, dest)).exceptionOrNull()?.message }.firstOrNull()
            }
            err = e; selL = emptySet(); selR = emptySet(); tick++; vm.refresh()
        }
    }

    Page2("Doppio pannello", onBack, actions = {
        IconButton({ transfer(false) }) { Icon(Icons.Default.ContentCopy, "Copia nell'altro pannello") }
        IconButton({ transfer(true) }) { Icon(Icons.Default.ContentCut, "Sposta nell'altro pannello") }
    }) {
        Column(Modifier.fillMaxSize()) {
            err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
            Pane(left, tick, selL, active == 0, Modifier.weight(1f), { left = it; selL = emptySet() }, { selL = it }, { active = 0 }, vm, onOpenFile)
            HorizontalDivider()
            Pane(right, tick, selR, active == 1, Modifier.weight(1f), { right = it; selR = emptySet() }, { selR = it }, { active = 1 }, vm, onOpenFile)
        }
    }
}

// ---------- Terminale ----------

@Composable
fun TerminalScreen(vm: FileViewModel, onBack: () -> Unit) {
    var cwd by remember { mutableStateOf(vm.currentDir) }
    var cmd by remember { mutableStateOf("") }
    var log by remember { mutableStateOf("") }
    var root by remember { mutableStateOf(false) }
    var rootOk by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberScrollState()

    fun run() {
        val c = cmd.trim(); if (c.isEmpty()) return
        cmd = ""
        if (c.startsWith("cd ")) {
            val t = c.removePrefix("cd ").trim()
            val d = if (t.startsWith("/")) File(t) else File(cwd, t)
            if (d.isDirectory) { cwd = d.canonicalFile; log += "$ $c\n" } else log += "$ $c\ncartella non trovata\n"
            return
        }
        scope.launch {
            log += "${if (root) "#" else "$"} $c\n"
            val out = withContext(Dispatchers.IO) { Shell.run(c, cwd, root) }
            log += out + "\n"
            listState.animateScrollTo(listState.maxValue)
        }
    }

    Page2("Terminale", onBack, actions = {
        Text("root", Modifier.padding(end = 4.dp))
        Switch(root, { on ->
            if (on) scope.launch {
                val ok = withContext(Dispatchers.IO) { Shell.hasRoot() }
                rootOk = ok; root = ok
            } else root = false
        })
    }) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            if (rootOk == false) Text("Root non disponibile su questo dispositivo", color = MaterialTheme.colorScheme.error)
            Text(cwd.absolutePath, style = MaterialTheme.typography.labelSmall)
            Text(
                log,
                Modifier.weight(1f).fillMaxWidth().verticalScroll(listState),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppTextField(cmd, { cmd = it }, "Comando", Modifier.weight(1f), onEnter = { run() }, autoFocus = true)
                Button({ run() }, Modifier.padding(start = 8.dp)) { Text("Esegui") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ls -la", "pwd", "df -h", "ls -la /data").forEach { q -> AssistChip({ cmd = q; run() }, { Text(q) }) }
            }
        }
    }
}

// ---------- Link temporaneo ----------

@Composable
fun LinkDialog(file: File, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var hours by remember { mutableLongStateOf(1L) }
    var url by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link temporaneo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${file.name} (${formatSize(file.length())})")
                Text("Valido solo sulla stessa rete Wi-Fi, finché l'app resta aperta.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1L, 6L, 24L).forEach { h -> FilterChip(hours == h, { hours = h }, { Text("${h}h") }) }
                }
                url?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (url == null) TextButton({
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { LinkServer.share(file, hours) } }
                        .onSuccess { url = it }.onFailure { err = it.message }
                }
            }) { Text("Crea link") }
            else TextButton({
                ctx.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Invia link")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Condividi") }
        },
        dismissButton = {
            Row {
                url?.let { u ->
                    TextButton({
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", u))
                    }) { Text("Copia") }
                }
                TextButton(onDismiss) { Text("Chiudi") }
            }
        },
    )
}

// ---------- Ricerca OCR ----------

@Composable
fun OcrScreen(vm: FileViewModel, onOpenFile: (File) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val index = remember { OcrIndex(ctx.filesDir) }
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<File>?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun search() {
        if (q.isBlank()) return
        scope.launch { results = withContext(Dispatchers.IO) { index.search(vm.currentDir, q.trim()) } }
    }

    Page2("Cerca nel testo (OCR)", onBack) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cartella: ${vm.currentDir.absolutePath}", style = MaterialTheme.typography.labelSmall)
            OutlinedButton({
                scope.launch {
                    progress = 0 to 1; info = null
                    val n = withContext(Dispatchers.IO) { runCatching { index.index(vm.currentDir) { a, b -> progress = a to b } }.getOrDefault(-1) }
                    progress = null
                    info = if (n < 0) "Errore durante l'indicizzazione" else "Indicizzati $n file nuovi o modificati"
                }
            }, enabled = progress == null) { Text("Indicizza immagini e PDF") }
            progress?.let { (a, b) -> LinearProgressIndicator(progress = { a.toFloat() / b.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth()); Text("$a / $b") }
            info?.let { Text(it) }
            AppTextField(q, { q = it }, "Parola da cercare", Modifier.fillMaxWidth(), onEnter = { search() })
            Button({ search() }) { Text("Cerca") }
            results?.let { r ->
                if (r.isEmpty()) Text("Nessun risultato")
                LazyColumn {
                    items(r, key = { it.absolutePath }) { f ->
                        ListItem(
                            headlineContent = { Text(f.name, maxLines = 1) },
                            supportingContent = { Text(f.parent ?: "") },
                            modifier = Modifier.clickable { onOpenFile(f) },
                        )
                    }
                }
            }
        }
    }
}
