package it.dalbrocante.gestorefile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import it.dalbrocante.gestorefile.data.FileItem
import it.dalbrocante.gestorefile.data.Tools
import it.dalbrocante.gestorefile.data.Vault
import it.dalbrocante.gestorefile.net.FtpServerService
import it.dalbrocante.gestorefile.util.Updater
import it.dalbrocante.gestorefile.util.formatDate
import it.dalbrocante.gestorefile.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleScaffold(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
            actions = { actions() },
        )
    }) { pad -> Box(Modifier.padding(pad).fillMaxSize()) { content() } }
}

@Composable
fun TrashScreen(vm: FileViewModel, onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<FileItem>>(vm.repo.trashItems()) }
    fun reload() { items = vm.repo.trashItems(); vm.refresh() }
    SimpleScaffold("Cestino", onBack, actions = {
        IconButton({ vm.repo.emptyTrash(); reload() }) { Icon(Icons.Default.DeleteForever, "Svuota") }
    }) {
        LazyColumn {
            items(items, key = { it.file.name }) { i ->
                ListItem(
                    headlineContent = { Text(i.name.substringAfter("__")) },
                    supportingContent = { Text("${formatSize(i.size)}  ${formatDate(i.modified)}") },
                    trailingContent = {
                        IconButton({ vm.repo.restoreFromTrash(i.file); reload() }) { Icon(Icons.Default.Restore, "Ripristina") }
                    },
                )
            }
        }
    }
}

@Composable
fun StorageScreen(vm: FileViewModel, onBack: () -> Unit) {
    var usage by remember { mutableStateOf<List<Pair<File, Long>>?>(null) }
    var big by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(vm.currentDir) {
        withContext(Dispatchers.IO) {
            usage = Tools.usage(vm.currentDir)
            big = Tools.biggest(vm.currentDir, 30)
        }
    }
    SimpleScaffold("Spazio: ${vm.currentDir.name.ifEmpty { "Memoria" }}", onBack) {
        val u = usage
        if (u == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else {
            val total = u.sumOf { it.second }.coerceAtLeast(1)
            LazyColumn {
                item { Text("Per cartella", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall) }
                items(u.take(20)) { (f, s) ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(f.name, maxLines = 1); Text(formatSize(s))
                        }
                        LinearProgressIndicator(progress = { s.toFloat() / total }, Modifier.fillMaxWidth())
                    }
                }
                item { Text("File più grandi", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall) }
                items(big) { f ->
                    ListItem(
                        headlineContent = { Text(f.name, maxLines = 1) },
                        supportingContent = { Text(f.parent ?: "") },
                        trailingContent = { Text(formatSize(f.length())) },
                        modifier = Modifier.clickable { f.parentFile?.let { vm.open(it); onBack() } },
                    )
                }
            }
        }
    }
}

@Composable
fun ToolsScreen(vm: FileViewModel, nav: Nav, onBack: () -> Unit, onVault: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ftp by remember { mutableStateOf(FtpServerService.running) }
    var pin by remember { mutableStateOf("%04d".format(Random.nextInt(10000))) }
    var dups by remember { mutableStateOf<List<List<File>>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var upd by remember { mutableStateOf<Updater.Info?>(null) }
    var upMsg by remember { mutableStateOf<String?>(null) }

    SimpleScaffold("Strumenti", onBack) {
        LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Strumenti avanzati", style = MaterialTheme.typography.titleSmall)
                listOf(
                    "Doppio pannello" to nav.openDual, "Strumenti PDF (unisci, dividi, firma)" to nav.openPdfTools,
                    "Cerca nel testo (OCR)" to nav.openOcr, "Scansiona documento" to nav.scan,
                    "Rete (FTP, SFTP, SMB)" to nav.openRemote, "Cloud e memorie esterne" to nav.openCloud,
                    "Terminale e root" to nav.openTerminal,
                ).forEach { (l, a) -> OutlinedButton(a, Modifier.fillMaxWidth().padding(top = 4.dp)) { Text(l) } }
            }
            item {
                Text("Aggiornamenti", style = MaterialTheme.typography.titleSmall)
                Text("Build installata: ${Updater.currentBuild()}")
                Button({
                    scope.launch {
                        val i = withContext(Dispatchers.IO) { Updater.check() }
                        if (i != null && Updater.isNewer(i)) upd = i else upMsg = if (i == null) "Controllo non riuscito" else "Sei aggiornato"
                    }
                }) { Text("Cerca aggiornamenti") }
                upMsg?.let { Text(it) }
            }
            item {
                Text("Server FTP", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (ftp) "ftp://${FtpServerService.localIp()}:${FtpServerService.PORT}  utente ${FtpServerService.USER}  password $pin"
                    else "Spento",
                )
                Button({
                    if (ftp) FtpServerService.stop(ctx) else FtpServerService.start(ctx, pin)
                    ftp = !ftp
                }) { Text(if (ftp) "Ferma" else "Avvia") }
            }
            item {
                Text("Cartella segreta", style = MaterialTheme.typography.titleSmall)
                Button(onVault) { Text("Apri Vault") }
            }
            item {
                Text("Duplicati in ${vm.currentDir.name.ifEmpty { "Memoria" }}", style = MaterialTheme.typography.titleSmall)
                Button({
                    scope.launch {
                        scanning = true
                        dups = withContext(Dispatchers.IO) { Tools.duplicates(vm.currentDir) }
                        scanning = false
                    }
                }) { Text("Cerca duplicati") }
                if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            dups?.let { groups ->
                if (groups.isEmpty()) item { Text("Nessun duplicato") }
                items(groups) { g ->
                    Column {
                        g.forEachIndexed { idx, f ->
                            ListItem(
                                headlineContent = { Text(f.name, maxLines = 1) },
                                supportingContent = { Text(f.parent ?: "") },
                                trailingContent = {
                                    if (idx > 0) IconButton({
                                        vm.repo.moveToTrash(f); dups = dups?.map { l -> l - f }?.filter { it.size > 1 }; vm.refresh()
                                    }) { Icon(Icons.Default.DeleteForever, "Cestina") }
                                },
                            )
                        }
                    }
                }
            }
            item {
                Text("Privacy foto", style = MaterialTheme.typography.titleSmall)
                Text("Rimuove la posizione GPS dai JPEG selezionati nella cartella corrente.")
                OutlinedButton({
                    val targets = vm.selection.ifEmpty { vm.items.map { it.file }.toSet() }
                        .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg") }
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { targets.count { runCatching { Tools.stripGps(it) }.isSuccess } }
                        status = "GPS rimosso da $n foto"
                    }
                }) { Text("Rimuovi GPS") }
                status?.let { Text(it) }
            }
        }
        upd?.let { UpdateDialog(it) { upd = null } }
    }
}

@Composable
fun VaultScreen(vm: FileViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val vault = remember { Vault(File(ctx.filesDir, "vault")) }
    var unlocked by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf(vault.items()) }

    LaunchedEffect(Unit) {
        val act = ctx as? FragmentActivity ?: run { unlocked = true; return@LaunchedEffect }
        val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked = true }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onBack() }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("Vault")
                .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build(),
        )
    }

    SimpleScaffold("Vault", onBack) {
        if (!unlocked) { Text("Autenticazione richiesta", Modifier.padding(16.dp)); return@SimpleScaffold }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ vm.selection.forEach { vault.add(it, vm.repo) }; vm.clearSelection(); vm.refresh(); items = vault.items() }, enabled = vm.selection.isNotEmpty()) {
                    Text("Sposta selezione nel Vault")
                }
            }
            LazyColumn {
                items(items, key = { it.name }) { f ->
                    ListItem(
                        headlineContent = { Text(f.name) },
                        supportingContent = { Text(formatSize(f.length())) },
                        trailingContent = {
                            IconButton({ vault.restore(f, vm.currentDir, vm.repo); vm.refresh(); items = vault.items() }) { Icon(Icons.Default.Restore, "Ripristina qui") }
                        },
                    )
                }
            }
        }
    }
}
