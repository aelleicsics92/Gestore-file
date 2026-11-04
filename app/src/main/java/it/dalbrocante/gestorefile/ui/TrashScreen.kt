package it.dalbrocante.gestorefile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.dalbrocante.gestorefile.data.FileItem
import it.dalbrocante.gestorefile.data.TrashEntry
import it.dalbrocante.gestorefile.util.formatDate
import it.dalbrocante.gestorefile.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TrashScreen(vm: FileViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<TrashEntry>?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmOne by remember { mutableStateOf<TrashEntry?>(null) }

    fun reload() { scope.launch { entries = withContext(Dispatchers.IO) { vm.repo.trashEntries() } } }
    LaunchedEffect(Unit) { reload() }

    fun io(block: () -> Unit) {
        scope.launch { withContext(Dispatchers.IO) { block() }; reload(); vm.refresh() }
    }

    Page2("Cestino", onBack) {
        val list = entries
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    ElevatedCard(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(18.dp)) {
                            Text(formatSize(list.sumOf { it.size }), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("${list.size} elementi nel cestino", color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Gli elementi vengono eliminati automaticamente dopo 30 giorni.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            if (list.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    { confirmEmpty = true }, Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                ) {
                                    Icon(Icons.Filled.DeleteForever, null); Spacer(Modifier.width(8.dp)); Text("Svuota tutto")
                                }
                            }
                        }
                    }
                }
                if (list.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Delete, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                        Text("Il cestino è vuoto", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(list, key = { it.file.name }) { e ->
                    CardItem {
                        FileThumb(FileItem(e.file, e.name, e.isDir, e.size, e.deletedAt, e.name.substringAfterLast('.', "").lowercase()), size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${formatSize(e.size)}  ·  ${formatDate(e.deletedAt)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                if (e.daysLeft == 0) "Eliminazione imminente" else "Eliminazione tra ${e.daysLeft} giorni",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (e.daysLeft <= 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                            )
                        }
                        IconButton({ io { vm.repo.restoreFromTrash(e.file) } }) { Icon(Icons.Filled.Restore, "Ripristina") }
                        IconButton({ confirmOne = e }) { Icon(Icons.Filled.DeleteForever, "Elimina definitivamente", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    if (confirmEmpty) ConfirmDialog("Svuota cestino", "Eliminare definitivamente tutti gli elementi? Operazione irreversibile.", { confirmEmpty = false }) {
        confirmEmpty = false; io { vm.repo.emptyTrash() }
    }
    confirmOne?.let { e ->
        ConfirmDialog("Elimina definitivamente", "Eliminare \"${e.name}\" per sempre?", { confirmOne = null }) {
            confirmOne = null; io { vm.repo.deleteFromTrash(e.file) }
        }
    }
}
