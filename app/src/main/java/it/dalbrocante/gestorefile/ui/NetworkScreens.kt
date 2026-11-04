package it.dalbrocante.gestorefile.ui

import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import it.dalbrocante.gestorefile.data.Protocol
import it.dalbrocante.gestorefile.data.RemoteClient
import it.dalbrocante.gestorefile.data.RemoteConfig
import it.dalbrocante.gestorefile.data.RemoteEntry
import it.dalbrocante.gestorefile.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------- FTP / SFTP / SMB ----------

@Composable
fun RemoteScreen(vm: FileViewModel, onOpenFile: (File) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var proto by remember { mutableStateOf(Protocol.SMB) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("445") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var client by remember { mutableStateOf<RemoteClient?>(null) }
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { client?.close() } }

    fun load(p: String) {
        val c = client ?: return
        scope.launch {
            busy = true
            runCatching { withContext(Dispatchers.IO) { c.list(p) } }
                .onSuccess { entries = it.sortedWith(compareByDescending<RemoteEntry> { e -> e.isDir }.thenBy { e -> e.name.lowercase() }); path = p; msg = null }
                .onFailure { msg = it.message }
            busy = false
        }
    }

    fun connect() {
        scope.launch {
            busy = true; msg = null
            val cfg = RemoteConfig(proto, host.trim(), port.toIntOrNull() ?: 0, user.trim(), pass, share.trim())
            runCatching { withContext(Dispatchers.IO) { RemoteClient.connect(cfg).also { it.list(if (proto == Protocol.SFTP) "." else "") } } }
                .onSuccess { client = it; load(if (proto == Protocol.SFTP) "." else if (proto == Protocol.FTP) "/" else "") }
                .onFailure { msg = it.message ?: "Connessione fallita" }
            busy = false
        }
    }

    fun parentOf(p: String): String {
        if (p == "/" || p.isEmpty() || p == ".") return p
        val up = p.trimEnd('/').substringBeforeLast('/', "")
        return if (up.isEmpty() && p.startsWith("/")) "/" else up
    }

    Page2(if (client == null) "Rete" else "$host $path", onBack, actions = {
        if (client != null) {
            IconButton({ load(parentOf(path)) }) { Icon(Icons.Default.ArrowUpward, "Su") }
            IconButton({
                val c = client ?: return@IconButton
                val files = vm.selection.filter { it.isFile }
                scope.launch {
                    busy = true
                    val e = withContext(Dispatchers.IO) {
                        files.mapNotNull { f -> runCatching { c.upload(f, RemoteClient.join(path, f.name)) }.exceptionOrNull()?.message }.firstOrNull()
                    }
                    msg = e ?: "Caricati ${files.size} file"; busy = false; load(path)
                }
            }, enabled = vm.selection.any { it.isFile }) { Icon(Icons.Default.Upload, "Carica selezione") }
        }
    }) {
        Column(Modifier.fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            msg?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            if (client == null) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Protocol.entries.forEach { p ->
                            FilterChip(proto == p, { proto = p; port = when (p) { Protocol.FTP -> "21"; Protocol.SFTP -> "22"; Protocol.SMB -> "445" } }, { Text(p.name) })
                        }
                    }
                    AppTextField(host, { host = it }, "Host o IP", Modifier.fillMaxWidth(), imeAction = ImeAction.Next, autoFocus = true)
                    AppTextField(port, { port = it }, "Porta", Modifier.fillMaxWidth(), imeAction = ImeAction.Next, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    if (proto == Protocol.SMB) AppTextField(share, { share = it }, "Condivisione (share)", Modifier.fillMaxWidth(), imeAction = ImeAction.Next)
                    AppTextField(user, { user = it }, "Utente", Modifier.fillMaxWidth(), imeAction = ImeAction.Next)
                    AppTextField(pass, { pass = it }, "Password", Modifier.fillMaxWidth(), onEnter = { connect() }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Button({ connect() }, enabled = host.isNotBlank() && !busy) { Text("Connetti") }
                }
            } else {
                LazyColumn {
                    items(entries, key = { it.path }) { e ->
                        CardListItem(
                            leadingContent = { Icon(if (e.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile, null) },
                            headlineContent = { Text(e.name, maxLines = 1) },
                            supportingContent = { if (!e.isDir) Text(formatSize(e.size)) },
                            trailingContent = {
                                if (!e.isDir) IconButton({
                                    val c = client ?: return@IconButton
                                    scope.launch {
                                        busy = true
                                        val r = runCatching { withContext(Dispatchers.IO) { c.download(e.path, File(vm.currentDir, e.name)) } }
                                        msg = r.exceptionOrNull()?.message ?: "Scaricato in ${vm.currentDir.name}"; busy = false; vm.refresh()
                                    }
                                }) { Icon(Icons.Default.Download, "Scarica nella cartella corrente") }
                            },
                            modifier = Modifier.clickable {
                                if (e.isDir) load(e.path) else {
                                    val c = client ?: return@clickable
                                    scope.launch {
                                        busy = true
                                        val tmp = File(ctx.cacheDir, "remote").apply { mkdirs() }.let { File(it, e.name) }
                                        val r = runCatching { withContext(Dispatchers.IO) { c.download(e.path, tmp) } }
                                        busy = false
                                        r.onSuccess { onOpenFile(tmp) }.onFailure { msg = it.message }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

// ---------- Cloud e memorie esterne tramite Storage Access Framework ----------

private fun DocumentFile.children() = listFiles().sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { (it.name ?: "").lowercase() })

@Composable
fun CloudScreen(vm: FileViewModel, onOpenFile: (File) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var roots by remember { mutableStateOf(ctx.contentResolver.persistedUriPermissions.map { it.uri }) }
    var stack by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            roots = ctx.contentResolver.persistedUriPermissions.map { it.uri }
        }
    }
    val cur = stack.lastOrNull()

    fun copyOut(doc: DocumentFile, dest: File) {
        ctx.contentResolver.openInputStream(doc.uri)!!.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
    }

    Page2(cur?.name ?: "Cloud e memorie esterne", { if (stack.isNotEmpty()) stack = stack.dropLast(1) else onBack() }, actions = {
        if (cur != null) IconButton({
            val files = vm.selection.filter { it.isFile }
            scope.launch {
                busy = true
                val e = withContext(Dispatchers.IO) {
                    files.mapNotNull { f ->
                        runCatching {
                            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
                            val d = cur.createFile(mime, f.name) ?: error("Creazione file fallita")
                            ctx.contentResolver.openOutputStream(d.uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
                        }.exceptionOrNull()?.message
                    }.firstOrNull()
                }
                msg = e ?: "Caricati ${files.size} file"; busy = false; stack = stack.toList()
            }
        }, enabled = vm.selection.any { it.isFile }) { Icon(Icons.Default.Upload, "Carica selezione qui") }
    }) {
        Column(Modifier.fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            msg?.let { Text(it, Modifier.padding(16.dp)) }
            if (cur == null) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Aggiungi una cartella di Google Drive, OneDrive, Dropbox (se l'app è installata), scheda SD o USB.")
                    Button({ picker.launch(null) }) { Text("Aggiungi cartella") }
                }
                LazyColumn {
                    items(roots, key = { it.toString() }) { u ->
                        CardListItem(
                            leadingContent = { Icon(Icons.Default.Folder, null) },
                            headlineContent = { Text(Uri.decode(u.lastPathSegment ?: u.toString()), maxLines = 1) },
                            supportingContent = { Text(u.authority ?: "") },
                            modifier = Modifier.clickable { DocumentFile.fromTreeUri(ctx, u)?.let { stack = listOf(it) } },
                        )
                    }
                }
            } else {
                val children = remember(stack) { cur.children() }
                LazyColumn {
                    items(children, key = { it.uri.toString() }) { d ->
                        CardListItem(
                            leadingContent = { Icon(if (d.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null) },
                            headlineContent = { Text(d.name ?: "?", maxLines = 1) },
                            supportingContent = { if (d.isFile) Text(formatSize(d.length())) },
                            trailingContent = {
                                if (d.isFile) IconButton({
                                    scope.launch {
                                        busy = true
                                        val r = runCatching { withContext(Dispatchers.IO) { copyOut(d, File(vm.currentDir, d.name ?: "file")) } }
                                        msg = r.exceptionOrNull()?.message ?: "Scaricato in ${vm.currentDir.name}"; busy = false; vm.refresh()
                                    }
                                }) { Icon(Icons.Default.Download, "Scarica nella cartella corrente") }
                            },
                            modifier = Modifier.clickable {
                                if (d.isDirectory) stack = stack + d else scope.launch {
                                    busy = true
                                    val tmp = File(File(ctx.cacheDir, "cloud").apply { mkdirs() }, d.name ?: "file")
                                    val r = runCatching { withContext(Dispatchers.IO) { copyOut(d, tmp) } }
                                    busy = false
                                    r.onSuccess { onOpenFile(tmp) }.onFailure { msg = it.message }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
