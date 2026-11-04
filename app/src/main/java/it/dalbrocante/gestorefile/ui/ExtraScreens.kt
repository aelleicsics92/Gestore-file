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
