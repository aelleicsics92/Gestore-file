package it.dalbrocante.gestorefile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.dalbrocante.gestorefile.data.FileItem
import it.dalbrocante.gestorefile.data.SortMode
import it.dalbrocante.gestorefile.util.ARCHIVE_EXT
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

private enum class Sheet { NONE, NEW, MENU, MORE }

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
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    val sel = vm.selection
    val inSelection = sel.isNotEmpty()
    val list: List<FileItem> = vm.searchResults ?: vm.items

    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it); vm.message = null }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            if (inSelection) {
                TopAppBar(
                    title = { Text("${sel.size} selezionati", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = { IconButton({ vm.clearSelection() }) { Icon(Icons.Filled.Close, "Annulla selezione") } },
                    actions = { IconButton({ vm.selectAll() }) { Icon(Icons.Filled.SelectAll, "Seleziona tutto") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            } else {
                Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                vm.currentDir.name.ifEmpty { "Memoria" },
                                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (vm.searchResults != null) "${list.size} risultati" else if (vm.loading && list.isEmpty()) "Caricamento…" else "${list.size} elementi",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalIconButton({ dlg = Dlg.Search }) { Icon(Icons.Filled.Search, "Cerca") }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalIconButton({ sheet = Sheet.MENU }) { Icon(Icons.Filled.Tune, "Menu") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Breadcrumb(vm.currentDir, vm.root) { vm.open(it) }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (vm.loading || vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).align(Alignment.TopCenter))

            if (list.isEmpty() && !vm.loading) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.FolderOpen, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(if (vm.searchResults != null) "Nessun risultato" else "Cartella vuota", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 140.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.file.absolutePath }, contentType = { if (it.isDir) 0 else 1 }) { item ->
                    val selected = item.file in sel
                    FileCard(
                        item, selected, inSelection,
                        onClick = {
                            when {
                                inSelection -> vm.toggleSelect(item.file)
                                item.isDir -> vm.open(item.file)
                                item.ext in ARCHIVE_EXT && item.ext != "cbz" && item.ext != "cbr" -> nav.extractArchive(item.file)
                                else -> nav.openFile(item.file)
                            }
                        },
                        onLongClick = { vm.toggleSelect(item.file) },
                    )
                }
            }

            // Azioni flottanti
            Column(
                Modifier.align(Alignment.BottomEnd).padding(20.dp).navigationBarsPadding(),
                horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AnimatedVisibility(vm.clipboard.isNotEmpty() && !inSelection, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
                    ExtendedFloatingActionButton(
                        { vm.paste() }, icon = { Icon(Icons.Filled.ContentPaste, null) },
                        text = { Text("Incolla (${vm.clipboard.size})") },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                AnimatedVisibility(!inSelection, enter = fadeIn(), exit = fadeOut()) {
                    FloatingActionButton({ sheet = Sheet.NEW }, shape = RoundedCornerShape(22.dp), containerColor = MaterialTheme.colorScheme.primary) {
                        Icon(Icons.Filled.Add, "Nuovo")
                    }
                }
            }

            // Barra azioni per la selezione
            AnimatedVisibility(
                inSelection, Modifier.align(Alignment.BottomCenter).padding(16.dp).navigationBarsPadding(),
                enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it },
            ) {
                Surface(shape = RoundedCornerShape(32.dp), tonalElevation = 8.dp, shadowElevation = 12.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        BarAction(Icons.Filled.ContentCopy, "Copia") { vm.copySelection() }
                        BarAction(Icons.Filled.ContentCut, "Taglia") { vm.cutSelection() }
                        BarAction(Icons.Filled.Share, "Condividi") { shareFiles(ctx, sel.toList()) }
                        BarAction(Icons.Filled.Delete, "Elimina", tint = MaterialTheme.colorScheme.error) { dlg = Dlg.TrashConfirm }
                        BarAction(Icons.Filled.MoreHoriz, "Altro") { sheet = Sheet.MORE }
                    }
                }
            }
        }
    }

    // ---- Bottom sheet ----
    if (sheet != Sheet.NONE) {
        ModalBottomSheet(onDismissRequest = { sheet = Sheet.NONE }, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (sheet) {
                    Sheet.NEW -> {
                        SectionHeader("Crea")
                        TileGrid(
                            listOf(
                                Tile(Icons.Filled.CreateNewFolder, "Nuova cartella", Color(0xFFFFB020)) { sheet = Sheet.NONE; dlg = Dlg.NewFolder },
                                Tile(Icons.Filled.NoteAdd, "Nuovo file", Color(0xFF3B82F6)) { sheet = Sheet.NONE; dlg = Dlg.NewFile },
                                Tile(Icons.Filled.DocumentScanner, "Scansiona", Color(0xFF8B5CF6)) { sheet = Sheet.NONE; nav.scan() },
                            ),
                        )
                    }
                    Sheet.MENU -> {
                        SectionHeader("Ordina per")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(SortMode.NAME to "Nome", SortMode.DATE to "Data", SortMode.SIZE to "Dimensione", SortMode.TYPE to "Tipo").forEach { (m, l) ->
                                FilterChip(vm.sort == m, { vm.changeSort(m) }, { Text(l) })
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Mostra file nascosti", Modifier.weight(1f))
                            Switch(vm.showHidden, { vm.toggleHidden() })
                        }
                        SectionHeader("Vai a")
                        TileGrid(
                            listOf(
                                Tile(Icons.Filled.Delete, "Cestino", Color(0xFF64748B)) { sheet = Sheet.NONE; nav.openTrash() },
                                Tile(Icons.Filled.PieChart, "Spazio", Color(0xFF5B4BDB)) { sheet = Sheet.NONE; nav.openStorage() },
                                Tile(Icons.Filled.BarChart, "Statistiche", Color(0xFF0EA5E9)) { sheet = Sheet.NONE; nav.openStats() },
                                Tile(Icons.Filled.Label, "Tag", Color(0xFFEC4899)) { sheet = Sheet.NONE; nav.openTags() },
                                Tile(Icons.Filled.VerticalSplit, "Doppio pannello", Color(0xFF14B8A6)) { sheet = Sheet.NONE; nav.openDual() },
                                Tile(Icons.Filled.Apps, "Strumenti", Color(0xFFFB7A3D)) { sheet = Sheet.NONE; nav.openTools() },
                            ),
                        )
                    }
                    Sheet.MORE -> {
                        SectionHeader("Azioni")
                        val one = sel.singleOrNull()
                        val tiles = buildList {
                            if (one != null) add(Tile(Icons.Filled.DriveFileRenameOutline, "Rinomina", Color(0xFF3B82F6)) { sheet = Sheet.NONE; dlg = Dlg.Rename(one) })
                            add(Tile(Icons.Filled.Inventory2, "Comprimi", Color(0xFF8B5CF6)) { sheet = Sheet.NONE; nav.compressSelection(sel.toList()) })
                            add(Tile(Icons.Filled.Label, "Tag", Color(0xFFEC4899)) { sheet = Sheet.NONE; nav.tagSelection(sel.toList()) })
                            if (one != null && one.isFile) add(Tile(Icons.Filled.Link, "Link temporaneo", Color(0xFF06B6D4)) { sheet = Sheet.NONE; nav.linkFile(one) })
                            if (sel.all { it.isFile && it.extension.equals("apk", true) }) {
                                add(Tile(Icons.Filled.Android, "Installa APK", Color(0xFF14B8A6)) { sheet = Sheet.NONE; nav.installApks(sel.toList()) })
                            }
                            add(Tile(Icons.Filled.DeleteForever, "Elimina per sempre", Color(0xFFE5484D)) { sheet = Sheet.NONE; dlg = Dlg.DeleteConfirm })
                        }
                        TileGrid(tiles)
                    }
                    Sheet.NONE -> Unit
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

@Composable
private fun BarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    IconButton(onClick) { Icon(icon, label, tint = tint) }
}

@Composable
private fun Breadcrumb(dir: File, root: File, onNav: (File) -> Unit) {
    val parts = remember(dir) {
        val rel = runCatching { dir.relativeTo(root).path }.getOrDefault("")
        val list = ArrayList<Pair<String, File>>()
        list += "Memoria" to root
        var cur = root
        rel.split(File.separatorChar).filter { it.isNotEmpty() && it != "." }.forEach { seg ->
            cur = File(cur, seg); list += seg to cur
        }
        list
    }
    val scroll = rememberScrollState()
    LaunchedEffect(dir) { scroll.animateScrollTo(scroll.maxValue) }
    Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, (name, f) ->
            val last = i == parts.lastIndex
            AssistChip(
                onClick = { if (!last) onNav(f) },
                label = { Text(name, maxLines = 1, fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal) },
                shape = RoundedCornerShape(14.dp),
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (last) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    labelColor = if (last) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = null,
            )
            if (!last) Icon(Icons.Filled.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCard(item: FileItem, selected: Boolean, selectionMode: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    ElevatedCard(
        shape = shape,
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (selected) 0.dp else 2.dp),
    ) {
        Row(
            Modifier.clip(shape)
                .semantics(mergeDescendants = true) {
                    contentDescription = (if (item.isDir) "Cartella " else "File ") + item.name +
                        (if (item.isDir) "" else ", " + formatSize(item.size)) + ", " + formatDate(item.modified) +
                        (if (selected) ", selezionato" else "")
                }
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileThumb(item)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (item.isDir) formatDate(item.modified) else "${formatSize(item.size)}  ·  ${formatDate(item.modified)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            if (selectionMode) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                }
            } else if (item.isDir) {
                Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
