package it.dalbrocante.gestorefile.ui

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import it.dalbrocante.gestorefile.data.ArchiveEntryInfo
import it.dalbrocante.gestorefile.data.ArchiveManager
import it.dalbrocante.gestorefile.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Selettore cartella di destinazione, con creazione cartella. */
@Composable
fun FolderPickerDialog(start: File, onDismiss: () -> Unit, onPick: (File) -> Unit) {
    var cur by remember { mutableStateOf(start) }
    var newName by remember { mutableStateOf<String?>(null) }
    val root = Environment.getExternalStorageDirectory()
    val dirs = remember(cur) { cur.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() } ?: emptyList() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scegli cartella") },
        text = {
            Column {
                Text(cur.absolutePath, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                LazyColumn(Modifier.height(280.dp)) {
                    if (cur != root && cur.parentFile != null) {
                        item { ListItem(headlineContent = { Text("..") }, modifier = Modifier.clickable { cur = cur.parentFile!! }) }
                    }
                    items(dirs) { d ->
                        ListItem(
                            leadingContent = { Icon(Icons.Default.Folder, null) },
                            headlineContent = { Text(d.name) },
                            modifier = Modifier.clickable { cur = d },
                        )
                    }
                }
                TextButton({ newName = "" }) { Text("Nuova cartella") }
            }
        },
        confirmButton = { TextButton({ onPick(cur) }) { Text("Scegli questa") } },
        dismissButton = { TextButton(onDismiss) { Text("Annulla") } },
    )
    newName?.let {
        InputDialog("Nuova cartella", "Nome", onDismiss = { newName = null }) { n ->
            File(cur, n).mkdirs(); newName = null; cur = File(cur, n)
        }
    }
}

private data class VEntry(val path: String, val name: String, val isDir: Boolean, val size: Long)

/** Contenuto della "cartella virtuale" prefix; le cartelle implicite (senza voce esplicita) sono ricostruite dai percorsi. */
private fun childrenOf(all: List<ArchiveEntryInfo>, prefix: String): List<VEntry> {
    val out = LinkedHashMap<String, VEntry>()
    for (e in all) {
        val p = e.path.trimStart('/').trimEnd('/')
        if (p.isEmpty() || !p.startsWith(prefix) || p == prefix.trimEnd('/')) continue
        val rest = p.removePrefix(prefix)
        val first = rest.substringBefore('/')
        val dir = e.isDir || rest.contains('/')
        val path = if (dir) prefix + first + "/" else p
        if (path !in out) out[path] = VEntry(path, first, dir, if (dir) 0L else e.size)
    }
    return out.values.sortedWith(compareByDescending<VEntry> { it.isDir }.thenBy { it.name.lowercase() })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(archive: File, onBack: () -> Unit, onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<ArchiveEntryInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var needPassword by remember { mutableStateOf(false) }
    var pickFor by remember { mutableStateOf<String?>(null) } // "" = tutto, altrimenti voce o cartella
    var cwd by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<File?>(null) }
    var menuFor by remember { mutableStateOf<VEntry?>(null) }

    fun load() {
        scope.launch {
            busy = true; error = null
            val r = withContext(Dispatchers.IO) { runCatching { ArchiveManager.list(archive, password.ifEmpty { null }) } }
            r.onSuccess { entries = it; needPassword = false }
                .onFailure { error = it.message; needPassword = ArchiveManager.isEncrypted(archive) }
            busy = false
        }
    }
    LaunchedEffect(archive) { needPassword = ArchiveManager.isEncrypted(archive); if (!needPassword) load() }

    fun extract(entry: String?, dest: File) {
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) {
                runCatching { ArchiveManager.extract(archive, dest, password.ifEmpty { null }, entry, flat = true) }
            }
            busy = false
            r.onSuccess { onDone() }.onFailure { error = it.message }
        }
    }

    /** Estrae SOLO la voce scelta in cache e la apre (viewer interno o app di sistema). */
    fun preview(e: VEntry) {
        scope.launch {
            busy = true; error = null
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(ctx.cacheDir, "archive_preview").apply { deleteRecursively(); mkdirs() }
                    ArchiveManager.extract(archive, dir, password.ifEmpty { null }, e.path, flat = true)
                    File(dir, e.name).also { require(it.exists()) { "Estrazione fallita" } }
                }
            }
            busy = false
            r.onSuccess { f ->
                if (hasInternalViewer(f.extension.lowercase())) viewing = f
                else it.dalbrocante.gestorefile.util.openExternal(ctx, f)
            }.onFailure { error = it.message }
        }
    }

    androidx.activity.compose.BackHandler(viewing != null) { viewing = null }
    androidx.activity.compose.BackHandler(viewing == null && cwd.isNotEmpty()) {
        cwd = cwd.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
    }

    viewing?.let { f -> ViewerScreen(f, null) { viewing = null }; return }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(archive.name, maxLines = 1) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            if (needPassword && entries == null) {
                Column(Modifier.padding(16.dp)) {
                    AppTextField(password, { password = it }, "Password", onEnter = { load() }, autoFocus = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Button({ load() }, Modifier.padding(top = 8.dp)) { Text("Apri") }
                }
            } else {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ extract(null, archive.parentFile!!) }) { Text("Estrai tutto qui") }
                    OutlinedButton({ pickFor = "" }) { Text("Estrai in...") }
                }
                // Percorso virtuale cliccabile
                Row(
                    Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    val segs = cwd.trimEnd('/').split('/').filter { it.isNotEmpty() }
                    androidx.compose.material3.AssistChip({ cwd = "" }, { Text(archive.name, maxLines = 1) }, shape = RoundedCornerShape(14.dp))
                    segs.forEachIndexed { i, sg ->
                        Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp))
                        androidx.compose.material3.AssistChip(
                            { cwd = segs.take(i + 1).joinToString("/") + "/" }, { Text(sg, maxLines = 1) }, shape = RoundedCornerShape(14.dp),
                        )
                    }
                }
                val visible = remember(entries, cwd) { childrenOf(entries ?: emptyList(), cwd) }
                LazyColumn(
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visible, key = { it.path }) { e ->
                        CardItem(onClick = { if (e.isDir) cwd = e.path else preview(e) }) {
                            FileThumb(
                                it.dalbrocante.gestorefile.data.FileItem(File(e.name), e.name, e.isDir, e.size, 0L, e.name.substringAfterLast('.', "").lowercase()),
                                size = 44.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                Text(if (e.isDir) "Cartella" else formatSize(e.size), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                            }
                            IconButton({ pickFor = e.path }) { Icon(Icons.Default.Unarchive, "Estrai solo questo") }
                        }
                    }
                }
            }
        }
    }

    pickFor?.let { entry ->
        FolderPickerDialog(archive.parentFile ?: Environment.getExternalStorageDirectory(), { pickFor = null }) { dest ->
            pickFor = null
            extract(entry.ifEmpty { null }, dest)
        }
    }
}

@Composable
fun CompressDialog(sources: List<File>, onDismiss: () -> Unit, onDone: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(if (sources.size == 1) sources[0].nameWithoutExtension else "archivio") }
    var tarGz by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(5f) }
    var password by remember { mutableStateOf("") }
    val parent = sources.first().parentFile!!
    val run = {
        scope.launch {
            val ext = if (tarGz) ".tar.gz" else ".zip"
            val target = File(parent, name.trim() + ext)
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (tarGz) ArchiveManager.createTarGz(sources, target)
                    else ArchiveManager.createZip(sources, target, level.toInt(), password.ifEmpty { null })
                }
            }
            onDone(r.exceptionOrNull()?.message)
        }
        Unit
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crea archivio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(name, { name = it }, "Nome", imeAction = ImeAction.Next, autoFocus = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!tarGz, { tarGz = false }, { Text("ZIP") })
                    FilterChip(tarGz, { tarGz = true }, { Text("TAR.GZ") })
                }
                if (!tarGz) {
                    Text("Compressione: ${level.toInt()}")
                    Slider(level, { level = it }, valueRange = 1f..9f, steps = 7)
                    AppTextField(password, { password = it }, "Password (opzionale)", onEnter = { run() })
                }
            }
        },
        confirmButton = { TextButton({ run() }) { Text("Crea") } },
        dismissButton = { TextButton(onDismiss) { Text("Annulla") } },
    )
}
