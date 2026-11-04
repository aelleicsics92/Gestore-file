package it.dalbrocante.gestorefile.ui

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.dalbrocante.gestorefile.data.FileItem
import it.dalbrocante.gestorefile.data.Tools
import it.dalbrocante.gestorefile.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
private fun LegendCard(color: Color, title: String, subtitle: String, trailing: String, selected: Boolean, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (selected) 0.dp else 2.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(trailing, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun FileRowCompact(file: File, onClick: () -> Unit, onFolder: () -> Unit) {
    ElevatedCard(
        onClick = onClick, shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FileThumb(FileItem(file), size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(file.parent ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(formatSize(if (file.isFile) file.length() else 0L), style = MaterialTheme.typography.labelLarge)
            IconButton(onFolder) { Icon(androidx.compose.material.icons.Icons.Filled.FolderOpen, "Apri cartella") }
        }
    }
}

// ---------- Statistiche per categoria ----------

private data class Cat(val kind: FileKind, val count: Int, val size: Long, val top: List<File>)

private fun computeCats(root: File): List<Cat> {
    class Acc { var count = 0; var size = 0L; val files = ArrayList<Pair<File, Long>>() }
    val map = LinkedHashMap<String, Pair<FileKind, Acc>>()
    root.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isFile }.forEach { f ->
        val kind = categoryOf(f.extension.lowercase())
        val acc = map.getOrPut(kind.label) { kind to Acc() }.second
        val len = f.length()
        acc.count++; acc.size += len; acc.files += f to len
        if (acc.files.size > 300) { acc.files.sortByDescending { it.second }; while (acc.files.size > 60) acc.files.removeAt(acc.files.lastIndex) }
    }
    return map.values.map { (k, a) -> Cat(k, a.count, a.size, a.files.sortedByDescending { it.second }.take(30).map { it.first }) }
        .sortedByDescending { it.size }
}

@Composable
fun StatsScreen(vm: FileViewModel, onBack: () -> Unit, onOpenFile: (File) -> Unit) {
    var cats by remember { mutableStateOf<List<Cat>?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(vm.currentDir) {
        cats = null; selected = null
        cats = withContext(Dispatchers.IO) { computeCats(vm.currentDir) }
    }
    Page2("Statistiche", onBack) {
        val c = cats?.filter { it.size > 0 }
        if (c == null) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("Analisi di ${vm.currentDir.name.ifEmpty { "Memoria" }}…")
            }
        } else if (c.isEmpty()) {
            Text("Nessun file da analizzare", Modifier.padding(24.dp))
        } else {
            val total = c.sumOf { it.size }
            val files = c.sumOf { it.count }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val sel = selected?.let { c.getOrNull(it) }
                        DonutChart(
                            c.map { Slice(it.kind.label, it.size, it.kind.color) }, selected, { selected = it },
                            centerTop = sel?.let { formatSize(it.size) } ?: formatSize(total),
                            centerBottom = sel?.let { "${it.kind.label}\n${it.count} file" } ?: "$files file",
                        )
                    }
                }
                itemsIndexed(c) { i, cat ->
                    LegendCard(cat.kind.color, cat.kind.label, "${cat.count} file", "${formatSize(cat.size)}  ·  ${(cat.size * 100 / total.coerceAtLeast(1))}%", selected == i) {
                        selected = if (selected == i) null else i
                    }
                }
                selected?.let { s ->
                    val cat = c[s]
                    item { SectionHeader("File più grandi: ${cat.kind.label}") }
                    items(cat.top, key = { it.absolutePath }) { f ->
                        FileRowCompact(f, { onOpenFile(f) }) { f.parentFile?.let { vm.open(it) }; onBack() }
                    }
                }
            }
        }
    }
}

// ---------- Analizzatore spazio ----------

@Composable
fun StorageScreen(vm: FileViewModel, onBack: () -> Unit, onOpenFile: (File) -> Unit) {
    var usage by remember { mutableStateOf<List<Pair<File?, Long>>?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var big by remember { mutableStateOf<List<File>>(emptyList()) }
    val device = remember {
        runCatching {
            val st = StatFs(Environment.getExternalStorageDirectory().path)
            st.totalBytes to (st.totalBytes - st.availableBytes)
        }.getOrNull()
    }
    LaunchedEffect(vm.currentDir) {
        usage = null; selected = null
        usage = withContext(Dispatchers.IO) {
            val all = Tools.usage(vm.currentDir).filter { it.second > 0 }
            val top = all.take(7).map { it.first as File? to it.second }
            val rest = all.drop(7).sumOf { it.second }
            if (rest > 0) top + (null to rest) else top
        }
    }
    LaunchedEffect(selected, usage) {
        val u = usage; val i = selected
        big = if (u != null && i != null) {
            val f = u[i].first
            if (f == null) emptyList() else withContext(Dispatchers.IO) { if (f.isDirectory) Tools.biggest(f, 20) else listOf(f) }
        } else emptyList()
    }
    Page2("Spazio: ${vm.currentDir.name.ifEmpty { "Memoria" }}", onBack) {
        val u = usage
        if (u == null) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("Calcolo dimensioni…")
            }
        } else if (u.isEmpty()) {
            Text("Cartella vuota", Modifier.padding(24.dp))
        } else {
            val total = u.sumOf { it.second }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                device?.let { (t, used) ->
                    item {
                        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Dispositivo: ${formatSize(used)} usati su ${formatSize(t)}",
                                Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val sel = selected?.let { u.getOrNull(it) }
                        DonutChart(
                            u.mapIndexed { i, (f, s) -> Slice(f?.name ?: "Altro", s, ChartPalette[i % ChartPalette.size]) }, selected, { selected = it },
                            centerTop = formatSize(sel?.second ?: total),
                            centerBottom = sel?.let { it.first?.name ?: "Altro" } ?: vm.currentDir.name.ifEmpty { "Memoria" },
                        )
                    }
                }
                itemsIndexed(u) { i, (f, s) ->
                    LegendCard(
                        ChartPalette[i % ChartPalette.size], f?.name ?: "Altro (cartelle minori)",
                        if (f?.isDirectory == true) "Cartella" else if (f == null) "" else "File",
                        "${formatSize(s)}  ·  ${(s * 100 / total.coerceAtLeast(1))}%", selected == i,
                    ) { selected = if (selected == i) null else i }
                }
                selected?.let { s ->
                    val f = u[s].first
                    if (f != null && f.isDirectory) item {
                        Button({ vm.open(f); onBack() }, Modifier.fillMaxWidth()) { Text("Apri ${f.name}") }
                    }
                    if (big.isNotEmpty()) {
                        item { SectionHeader("File più grandi") }
                        items(big, key = { it.absolutePath }) { b -> FileRowCompact(b, { onOpenFile(b) }) { b.parentFile?.let { vm.open(it) }; onBack() } }
                    }
                }
            }
        }
    }
}
