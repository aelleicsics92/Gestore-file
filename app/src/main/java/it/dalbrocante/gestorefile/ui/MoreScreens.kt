package it.dalbrocante.gestorefile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import it.dalbrocante.gestorefile.data.AutoOrganizer
import it.dalbrocante.gestorefile.data.AutoRule
import it.dalbrocante.gestorefile.data.Store
import it.dalbrocante.gestorefile.util.ARCHIVE_EXT
import it.dalbrocante.gestorefile.util.AUDIO_EXT
import it.dalbrocante.gestorefile.util.IMAGE_EXT
import it.dalbrocante.gestorefile.util.TEXT_EXT
import it.dalbrocante.gestorefile.util.VIDEO_EXT
import it.dalbrocante.gestorefile.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) = Page2(title, onBack, content = content)

/** Dialog per assegnare un tag ai file selezionati (suggerisce i tag esistenti). */
@Composable
fun TagDialog(store: Store, files: List<File>, onDismiss: () -> Unit, onDone: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val existing = remember { store.allTags() }
    val submit = { if (text.isNotBlank()) { store.addTag(files, text.trim()); onDone() } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aggiungi tag") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(text, { text = it }, "Tag", onEnter = submit, autoFocus = true)
                existing.filter { it.contains(text, true) && it != text }.take(6).forEach {
                    AssistChip({ text = it }, { Text(it) })
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(submit) { Text("Aggiungi") } },
        dismissButton = { androidx.compose.material3.TextButton(onDismiss) { Text("Annulla") } },
    )
}

@Composable
fun TagsScreen(store: Store, onOpenFolder: (File) -> Unit, onBack: () -> Unit) {
    var tags by remember { mutableStateOf(store.allTags()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(selected) { files = selected?.let { store.filesWithTag(it) } ?: emptyList() }
    Page("Tag", onBack) {
        LazyColumn {
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tags.isEmpty()) Text("Nessun tag. Seleziona dei file e usa l'icona Tag.")
                    tags.forEach { t -> FilterChip(selected == t, { selected = if (selected == t) null else t }, { Text(t) }) }
                }
            }
            items(files, key = { it.absolutePath }) { f ->
                CardListItem(
                    headlineContent = { Text(f.name, maxLines = 1) },
                    supportingContent = { Text(f.parent ?: "") },
                    trailingContent = {
                        IconButton({
                            selected?.let { store.removeTag(f.absolutePath, it) }
                            tags = store.allTags(); files = selected?.let { store.filesWithTag(it) } ?: emptyList()
                            if (selected !in tags) selected = null
                        }) { Icon(Icons.Default.Close, "Togli tag") }
                    },
                    modifier = Modifier.clickable { (if (f.isDirectory) f else f.parentFile)?.let(onOpenFolder) },
                )
            }
        }
    }
}

@Composable
fun RulesScreen(vm: FileViewModel, store: Store, onBack: () -> Unit) {
    var rules by remember { mutableStateOf(store.loadRules()) }
    var result by remember { mutableStateOf<String?>(null) }
    var exts by remember { mutableStateOf("pdf") }
    var target by remember { mutableStateOf("Documents/Smistati") }
    var date by remember { mutableStateOf(false) }
    var watch by remember { mutableStateOf("Download") }
    val root = vm.root
    fun resolve(p: String) = if (p.startsWith("/")) File(p) else File(root, p)
    val add = {
        if (exts.isNotBlank() && target.isNotBlank() && watch.isNotBlank()) {
            rules = rules + AutoRule(
                resolve(watch.trim()),
                exts.split(",", " ").map { it.trim().lowercase().removePrefix(".") }.filter { it.isNotEmpty() }.toSet(),
                resolve(target.trim()), date,
            )
            store.saveRules(rules)
        }
    }
    Page("Regole di smistamento", onBack) {
        LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Nuova regola", style = MaterialTheme.typography.titleSmall)
                AppTextField(watch, { watch = it }, "Cartella da controllare", Modifier.fillMaxWidth(), imeAction = ImeAction.Next)
                AppTextField(exts, { exts = it }, "Estensioni (pdf, jpg)", Modifier.fillMaxWidth().padding(top = 8.dp), imeAction = ImeAction.Next)
                AppTextField(target, { target = it }, "Sposta in", Modifier.fillMaxWidth().padding(top = 8.dp), onEnter = add)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(date, { date = it }); Text("Aggiungi la data al nome")
                }
                Button(add) { Text("Aggiungi regola") }
            }
            items(rules) { r ->
                CardListItem(
                    headlineContent = { Text("${r.exts.joinToString(", ")}  →  ${r.target.name}") },
                    supportingContent = { Text(r.watch.absolutePath) },
                    trailingContent = {
                        IconButton({ rules = rules - r; store.saveRules(rules) }) { Icon(Icons.Default.Delete, "Elimina") }
                    },
                )
            }
            item {
                OutlinedButton({
                    result = "Spostati ${AutoOrganizer.run(rules, vm.repo)} file"
                    vm.refresh()
                }, enabled = rules.isNotEmpty()) { Text("Esegui ora") }
                result?.let { Text(it) }
            }
        }
    }
}
