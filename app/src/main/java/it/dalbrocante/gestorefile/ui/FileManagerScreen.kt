package it.dalbrocante.gestorefile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import it.dalbrocante.gestorefile.data.FileItem
import it.dalbrocante.gestorefile.data.SortMode
import it.dalbrocante.gestorefile.util.ARCHIVE_EXT
import it.dalbrocante.gestorefile.util.AUDIO_EXT
import it.dalbrocante.gestorefile.util.IMAGE_EXT
import it.dalbrocante.gestorefile.util.VIDEO_EXT
import it.dalbrocante.gestorefile.util.formatDate
import it.dalbrocante.gestorefile.util.formatSize
import it.dalbrocante.gestorefile.util.shareFiles
import java.io.File

private sealed interface Dlg {
    data object NewFolder : Dlg
    data object NewFile : Dlg
    data class Rename(val f: File) : Dlg
    data object TrashConfirm : Dlg
    data object DeleteConfirm : Dlg
    data object Search : Dlg
}

/** Azioni di navigazione verso altre schermate (gestite da MainActivity). */
data class Nav(
    val openFile: (File) -> Unit,
    val extractArchive: (File) -> Unit,
    val compressSelection: (List<File>) -> Unit,
    val openTrash: () -> Unit,
    val openStorage: () -> Unit,
    val openTools: () -> Unit,
    val tagSelection: (List<File>) -> Unit,
    val openTags: () -> Unit,
    val openRules: () -> Unit,
    val openStats: () -> Unit,
    val openDual: () -> Unit,
    val openPdfTools: () -> Unit,
    val openOcr: () -> Unit,
    val openRemote: () -> Unit,
    val openCloud: () -> Unit,
    val openTerminal: () -> Unit,
    val scan: () -> Unit,
    val linkFile: (File) -> Unit,
    val installApks: (List<File>) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileManagerScreen(vm: FileViewModel, nav: Nav) {
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var menu by remember { mutableStateOf(false) }
    val sel = vm.selection
    val list: List<FileItem> = vm.searchResults ?: vm.items

    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it); vm.message = null }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (sel.isNotEmpty()) "${sel.size} selezionati" else vm.currentDir.name.ifEmpty { "Memoria" },
                        maxLines = 1,
                    )
                },
                actions = {
                    if (sel.isNotEmpty()) {
                        IconButton({ vm.selectAll() }) { Icon(Icons.Default.SelectAll, "Seleziona tutto") }
                        IconButton({ vm.copySelection() }) { Icon(Icons.Default.ContentCopy, "Copia") }
                        IconButton({ vm.cutSelection() }) { Icon(Icons.Default.ContentCut, "Taglia") }
                        IconButton({ shareFiles(ctx, sel.toList()) }) { Icon(Icons.Default.Share, "Condividi") }
                        IconButton({ dlg = Dlg.TrashConfirm }) { Icon(Icons.Default.Delete, "Elimina") }
                        if (sel.size == 1) {
                            IconButton({ dlg = Dlg.Rename(sel.first()) }) { Icon(Icons.Default.DriveFileRenameOutline, "Rinomina") }
                        }
                        IconButton({ nav.compressSelection(sel.toList()) }) { Icon(Icons.Default.Archive, "Comprimi") }
                        IconButton({ nav.tagSelection(sel.toList()) }) { Icon(Icons.Default.Label, "Tag") }
                        if (sel.size == 1 && sel.first().isFile) {
                            IconButton({ nav.linkFile(sel.first()) }) { Icon(Icons.Default.Link, "Link temporaneo") }
                        }
                        if (sel.all { it.isFile && it.extension.equals("apk", true) }) {
                            IconButton({ nav.installApks(sel.toList()) }) { Icon(Icons.Default.Android, "Installa APK") }
                        }
                    } else {
                        if (vm.clipboard.isNotEmpty()) {
                            IconButton({ vm.paste() }) { Icon(Icons.Default.ContentPaste, "Incolla") }
                        }
                        IconButton({ dlg = Dlg.Search }) { Icon(Icons.Default.Search, "Cerca") }
                        IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Nuovo file") }, { menu = false; dlg = Dlg.NewFile })
                            DropdownMenuItem({ Text("Ordina per nome") }, { menu = false; vm.changeSort(SortMode.NAME) })
                            DropdownMenuItem({ Text("Ordina per data") }, { menu = false; vm.changeSort(SortMode.DATE) })
                            DropdownMenuItem({ Text("Ordina per dimensione") }, { menu = false; vm.changeSort(SortMode.SIZE) })
                            DropdownMenuItem({ Text("Ordina per tipo") }, { menu = false; vm.changeSort(SortMode.TYPE) })
                            DropdownMenuItem({ Text(if (vm.showHidden) "Nascondi file nascosti" else "Mostra file nascosti") }, { menu = false; vm.toggleHidden() })
                            DropdownMenuItem({ Text("Cestino") }, { menu = false; nav.openTrash() })
                            DropdownMenuItem({ Text("Analizzatore spazio") }, { menu = false; nav.openStorage() })
                            DropdownMenuItem({ Text("Doppio pannello") }, { menu = false; nav.openDual() })
                            DropdownMenuItem({ Text("Scansiona documento") }, { menu = false; nav.scan() })
                            DropdownMenuItem({ Text("Tag") }, { menu = false; nav.openTags() })
                            DropdownMenuItem({ Text("Regole di smistamento") }, { menu = false; nav.openRules() })
                            DropdownMenuItem({ Text("Statistiche") }, { menu = false; nav.openStats() })
                            DropdownMenuItem({ Text("Strumenti") }, { menu = false; nav.openTools() })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton({ dlg = Dlg.NewFolder }) { Icon(Icons.Default.CreateNewFolder, "Nuova cartella") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                vm.currentDir.absolutePath,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                maxLines = 1,
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.file.absolutePath }) { item ->
                    val selected = item.file in sel
                    ListItem(
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            contentDescription = (if (item.isDir) "Cartella " else "File ") + item.name +
                                (if (item.isDir) "" else ", " + formatSize(item.size)) + ", " + formatDate(item.modified) +
                                (if (selected) ", selezionato" else "")
                        }.combinedClickable(
                            onClick = {
                                when {
                                    sel.isNotEmpty() -> vm.toggleSelect(item.file)
                                    item.isDir -> vm.open(item.file)
                                    item.ext in ARCHIVE_EXT && item.ext != "cbz" && item.ext != "cbr" -> nav.extractArchive(item.file)
                                    else -> nav.openFile(item.file)
                                }
                            },
                            onLongClick = { vm.toggleSelect(item.file) },
                        ),
                        leadingContent = {
                            if (sel.isNotEmpty()) Checkbox(selected, { vm.toggleSelect(item.file) })
                            else Icon(iconFor(item), null)
                        },
                        headlineContent = { Text(item.name, maxLines = 1) },
                        supportingContent = {
                            Text(
                                if (item.isDir) formatDate(item.modified)
                                else "${formatSize(item.size)}  ${formatDate(item.modified)}",
                            )
                        },
                    )
                }
            }
        }
    }

    when (val d = dlg) {
        Dlg.NewFolder -> InputDialog("Nuova cartella", "Nome", onDismiss = { dlg = null }) { vm.createFolder(it); dlg = null }
        Dlg.NewFile -> InputDialog("Nuovo file", "Nome", onDismiss = { dlg = null }) { vm.createFile(it); dlg = null }
        is Dlg.Rename -> InputDialog("Rinomina", "Nuovo nome", initial = d.f.name, onDismiss = { dlg = null }) {
            vm.rename(d.f, it); vm.clearSelection(); dlg = null
        }
        Dlg.TrashConfirm -> ConfirmDialog("Elimina", "Spostare nel cestino (30 giorni)?", { dlg = null }) {
            vm.trashSelection(); dlg = null
        }
        Dlg.DeleteConfirm -> ConfirmDialog("Elimina definitivamente", "Operazione irreversibile.", { dlg = null }) {
            vm.deleteSelectionPermanently(); dlg = null
        }
        Dlg.Search -> SearchDialog(onDismiss = { dlg = null }) { vm.search(it); dlg = null }
        null -> Unit
    }
}

private fun iconFor(i: FileItem) = when {
    i.isDir -> Icons.Default.Folder
    i.ext in IMAGE_EXT -> Icons.Default.Image
    i.ext in VIDEO_EXT -> Icons.Default.VideoFile
    i.ext in AUDIO_EXT -> Icons.Default.Audiotrack
    i.ext in ARCHIVE_EXT -> Icons.Default.Archive
    i.ext == "pdf" -> Icons.Default.PictureAsPdf
    i.ext in setOf("doc", "docx", "xls", "xlsx", "txt", "csv", "odt", "ods") -> Icons.Default.Description
    else -> Icons.Default.InsertDriveFile
}
