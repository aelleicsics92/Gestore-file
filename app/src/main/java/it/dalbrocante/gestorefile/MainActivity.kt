package it.dalbrocante.gestorefile

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import it.dalbrocante.gestorefile.data.ApkInstaller
import it.dalbrocante.gestorefile.ui.ArchiveScreen
import it.dalbrocante.gestorefile.ui.CloudScreen
import it.dalbrocante.gestorefile.ui.CompressDialog
import it.dalbrocante.gestorefile.ui.DualPaneScreen
import it.dalbrocante.gestorefile.ui.FileManagerScreen
import it.dalbrocante.gestorefile.ui.FileViewModel
import it.dalbrocante.gestorefile.ui.GestoreTheme
import it.dalbrocante.gestorefile.ui.LinkDialog
import it.dalbrocante.gestorefile.ui.Nav
import it.dalbrocante.gestorefile.ui.OcrScreen
import it.dalbrocante.gestorefile.ui.PdfToolsScreen
import it.dalbrocante.gestorefile.ui.RemoteScreen
import it.dalbrocante.gestorefile.ui.RulesScreen
import it.dalbrocante.gestorefile.ui.StatsScreen
import it.dalbrocante.gestorefile.ui.StorageScreen
import it.dalbrocante.gestorefile.ui.TagDialog
import it.dalbrocante.gestorefile.ui.TagsScreen
import it.dalbrocante.gestorefile.ui.TerminalScreen
import it.dalbrocante.gestorefile.ui.ToolsScreen
import it.dalbrocante.gestorefile.ui.TrashScreen
import it.dalbrocante.gestorefile.ui.UpdateDialog
import it.dalbrocante.gestorefile.ui.VaultScreen
import it.dalbrocante.gestorefile.ui.ViewerScreen
import it.dalbrocante.gestorefile.ui.hasInternalViewer
import it.dalbrocante.gestorefile.util.Updater
import it.dalbrocante.gestorefile.util.openExternal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface Route {
    data object Files : Route
    data class Viewer(val file: File) : Route
    data class Archive(val file: File) : Route
    data object Trash : Route
    data object Storage : Route
    data object Tools : Route
    data object Vault : Route
    data object Tags : Route
    data object Rules : Route
    data object Stats : Route
    data object Dual : Route
    data object PdfTools : Route
    data object Ocr : Route
    data object Remote : Route
    data object Cloud : Route
    data object Terminal : Route
}

class MainActivity : FragmentActivity() {

    private val vm: FileViewModel by viewModels()

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pdf?.uri ?: return@registerForActivityResult
        val dir = vm.currentDir
        val name = "Scansione_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ITALY).format(Date()) + ".pdf"
        Thread {
            runCatching {
                contentResolver.openInputStream(uri)!!.use { i -> File(dir, name).outputStream().use { o -> i.copyTo(o) } }
            }.onFailure { runOnUiThread { vm.message = "Salvataggio scansione fallito: ${it.message}" } }
            runOnUiThread { vm.refresh(); if (vm.message == null) vm.message = "Salvato $name" }
        }.start()
    }

    private fun scanDocument() {
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(this)
            .addOnSuccessListener { scanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { vm.message = "Scanner non disponibile: ${it.message}" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GestoreTheme {
                Surface(Modifier.fillMaxSize()) { Root() }
            }
        }
    }

    private fun hasAccess() =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    @Composable
    private fun Root() {
        var granted by remember { mutableStateOf(hasAccess()) }
        val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) { granted = hasAccess(); if (granted) vm.refresh() }
            }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs) }
        }

        if (!granted) {
            Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Text("Serve l'accesso a tutti i file per gestire la memoria.")
                Button({ requestAccess() }, Modifier.padding(top = 16.dp)) { Text("Concedi accesso") }
            }
            return
        }

        var route by remember { mutableStateOf<Route>(Route.Files) }
        var compress by remember { mutableStateOf<List<File>?>(null) }
        var tagging by remember { mutableStateOf<List<File>?>(null) }
        var linking by remember { mutableStateOf<File?>(null) }
        var update by remember { mutableStateOf<Updater.Info?>(null) }

        LaunchedEffect(Unit) {
            update = withContext(Dispatchers.IO) { Updater.check() }?.takeIf { Updater.isNewer(it) }
        }

        BackHandler(route != Route.Files || vm.currentDir != vm.root || vm.selection.isNotEmpty() || vm.searchResults != null) {
            if (route != Route.Files) route = Route.Files else vm.up()
        }

        val openFile: (File) -> Unit = { f ->
            if (hasInternalViewer(f.extension.lowercase())) route = Route.Viewer(f) else openExternal(this, f)
        }

        val nav = Nav(
            openFile = openFile,
            extractArchive = { route = Route.Archive(it) },
            compressSelection = { compress = it },
            openTrash = { route = Route.Trash },
            openStorage = { route = Route.Storage },
            openTools = { route = Route.Tools },
            tagSelection = { tagging = it },
            openTags = { route = Route.Tags },
            openRules = { route = Route.Rules },
            openStats = { route = Route.Stats },
            openDual = { route = Route.Dual },
            openPdfTools = { route = Route.PdfTools },
            openOcr = { route = Route.Ocr },
            openRemote = { route = Route.Remote },
            openCloud = { route = Route.Cloud },
            openTerminal = { route = Route.Terminal },
            scan = { route = Route.Files; scanDocument() },
            linkFile = { linking = it },
            installApks = { ApkInstaller.start(this, it); vm.clearSelection() },
        )

        when (val r = route) {
            Route.Files -> FileManagerScreen(vm, nav)
            is Route.Viewer -> ViewerScreen(r.file, vm.items.filter { !it.isDir }.map { it.file }) { route = Route.Files }
            is Route.Archive -> ArchiveScreen(r.file, { route = Route.Files }) { vm.refresh(); route = Route.Files }
            Route.Trash -> TrashScreen(vm) { route = Route.Files }
            Route.Storage -> StorageScreen(vm) { route = Route.Files }
            Route.Tools -> ToolsScreen(vm, nav, { route = Route.Files }) { route = Route.Vault }
            Route.Vault -> VaultScreen(vm) { route = Route.Tools }
            Route.Tags -> TagsScreen(vm.store, { vm.open(it); route = Route.Files }) { route = Route.Files }
            Route.Rules -> RulesScreen(vm, vm.store) { route = Route.Files }
            Route.Stats -> StatsScreen(vm) { route = Route.Files }
            Route.Dual -> DualPaneScreen(vm, openFile) { vm.refresh(); route = Route.Files }
            Route.PdfTools -> PdfToolsScreen(vm) { route = Route.Files }
            Route.Ocr -> OcrScreen(vm, openFile) { route = Route.Files }
            Route.Remote -> RemoteScreen(vm, openFile) { route = Route.Files }
            Route.Cloud -> CloudScreen(vm, openFile) { route = Route.Files }
            Route.Terminal -> TerminalScreen(vm) { route = Route.Files }
        }

        tagging?.let { src ->
            TagDialog(vm.store, src, { tagging = null }) { tagging = null; vm.clearSelection() }
        }
        compress?.let { src ->
            CompressDialog(src, { compress = null }) { err ->
                compress = null
                vm.clearSelection()
                vm.refresh()
                if (err != null) vm.message = err
            }
        }
        linking?.let { f -> LinkDialog(f) { linking = null; vm.clearSelection() } }
        update?.let { u -> UpdateDialog(u) { update = null } }
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            runCatching { startActivity(i) }.onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            requestPermissions(
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1,
            )
        }
    }
}
