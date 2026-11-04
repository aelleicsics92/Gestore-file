package it.dalbrocante.gestorefile.ui

import android.graphics.Bitmap
import android.webkit.WebView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import it.dalbrocante.gestorefile.data.ArchiveManager
import it.dalbrocante.gestorefile.data.PdfTools
import it.dalbrocante.gestorefile.data.SignPos
import it.dalbrocante.gestorefile.util.IMAGE_EXT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder

// ---------- Strumenti PDF ----------

private enum class PdfOp(val label: String) { MERGE("Unisci"), EXTRACT("Estrai pagine"), SPLIT("Dividi"), SIGN("Firma") }

@Composable
fun PdfToolsScreen(vm: FileViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pdfs = vm.selection.filter { it.isFile && it.extension.equals("pdf", true) }.sortedBy { it.name.lowercase() }
    var op by remember { mutableStateOf(PdfOp.MERGE) }
    var pages by remember { mutableStateOf("1") }
    var page by remember { mutableStateOf("1") }
    var pos by remember { mutableStateOf(SignPos.BOTTOM_RIGHT) }
    var strokes by remember { mutableStateOf(listOf<List<Pair<Float, Float>>>()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val dir = vm.currentDir
    val stamp = remember { java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ITALY).format(java.util.Date()) }

    fun run(block: () -> String) {
        scope.launch {
            busy = true; status = null
            status = withContext(Dispatchers.IO) { runCatching { PdfTools.init(ctx); block() }.getOrElse { "Errore: ${it.message}" } }
            busy = false; vm.refresh()
        }
    }

    Page2("Strumenti PDF", onBack) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Seleziona i PDF nella lista file, poi torna qui. Selezionati: ${pdfs.size}")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PdfOp.entries.forEach { o -> FilterChip(op == o, { op = o }, { Text(o.label) }) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (op) {
                PdfOp.MERGE -> Button({
                    run { PdfTools.merge(pdfs, File(dir, "unione_$stamp.pdf")); "Creato unione_$stamp.pdf" }
                }, enabled = pdfs.size >= 2 && !busy) { Text("Unisci ${pdfs.size} PDF") }

                PdfOp.EXTRACT -> {
                    AppTextField(pages, { pages = it }, "Pagine (es. 1-3,5)", Modifier.fillMaxWidth(), onEnter = {})
                    Button({
                        val src = pdfs.first()
                        run {
                            val sel = PdfTools.parseRanges(pages, PdfTools.pageCount(src))
                            check(sel.isNotEmpty()) { "Nessuna pagina valida" }
                            val out = File(dir, "${src.nameWithoutExtension}_estratto.pdf")
                            PdfTools.extractPages(src, sel, out); "Creato ${out.name}"
                        }
                    }, enabled = pdfs.size == 1 && !busy) { Text("Estrai") }
                }

                PdfOp.SPLIT -> Button({
                    val src = pdfs.first()
                    run {
                        val files = PdfTools.splitAll(src, File(dir, "${src.nameWithoutExtension}_pagine"))
                        "Create ${files.size} pagine in ${src.nameWithoutExtension}_pagine"
                    }
                }, enabled = pdfs.size == 1 && !busy) { Text("Dividi in una pagina per file") }

                PdfOp.SIGN -> {
                    Text("Disegna la firma")
                    SignaturePad(strokes) { strokes = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SignPos.entries.forEach { p ->
                            FilterChip(pos == p, { pos = p }, { Text(when (p) { SignPos.BOTTOM_RIGHT -> "In basso a destra"; SignPos.BOTTOM_LEFT -> "In basso a sinistra"; SignPos.CENTER -> "Al centro" }) })
                        }
                    }
                    AppTextField(page, { page = it }, "Pagina", Modifier.fillMaxWidth(), onEnter = {})
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ strokes = emptyList() }) { Text("Cancella") }
                        Button({
                            val src = pdfs.first()
                            val bmp: Bitmap? = PdfTools.renderSignature(strokes)
                            run {
                                checkNotNull(bmp) { "Disegna prima la firma" }
                                val n = page.trim().toIntOrNull() ?: 1
                                val out = File(dir, "${src.nameWithoutExtension}_firmato.pdf")
                                PdfTools.sign(src, bmp, n.coerceIn(1, PdfTools.pageCount(src)), pos, out); "Creato ${out.name}"
                            }
                        }, enabled = pdfs.size == 1 && strokes.isNotEmpty() && !busy) { Text("Applica firma") }
                    }
                }
            }
            status?.let { Text(it) }
        }
    }
}

@Composable
private fun SignaturePad(strokes: List<List<Pair<Float, Float>>>, onChange: (List<List<Pair<Float, Float>>>) -> Unit) {
    var current by remember { mutableStateOf(strokes) }
    if (strokes.isEmpty() && current.isNotEmpty()) current = emptyList()
    Canvas(
        Modifier.fillMaxWidth().height(160.dp).border(1.dp, MaterialTheme.colorScheme.outline)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o -> current = current + listOf(listOf(o.x to o.y)); onChange(current) },
                    onDrag = { change, _ ->
                        val last = current.lastOrNull() ?: return@detectDragGestures
                        current = current.dropLast(1) + listOf(last + (change.position.x to change.position.y))
                        onChange(current)
                    },
                )
            },
    ) {
        current.forEach { s ->
            if (s.size > 1) {
                val p = Path().apply { moveTo(s[0].first, s[0].second); s.drop(1).forEach { lineTo(it.first, it.second) } }
                drawPath(p, Color.Black, style = Stroke(width = 4f))
            }
        }
    }
}

// ---------- Lettori EPUB e fumetti ----------

@Composable
fun ComicReader(file: File) {
    val ctx = LocalContext.current
    var pages by remember { mutableStateOf<List<File>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(ctx.cacheDir, "reader/${file.name}_${file.lastModified()}")
                if (!dir.exists()) ArchiveManager.extract(file, dir)
                dir.walkTopDown().filter { it.isFile && it.extension.lowercase() in IMAGE_EXT }
                    .sortedBy { it.path.lowercase().replace(Regex("\\d+")) { m -> m.value.padStart(8, '0') } }.toList()
            }.onSuccess { pages = it }.onFailure { err = it.message }
        }
    }
    val p = pages
    when {
        err != null -> Text("Errore: $err", Modifier.padding(16.dp))
        p == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        p.isEmpty() -> Text("Nessuna immagine nel fumetto", Modifier.padding(16.dp))
        else -> {
            val state = rememberPagerState { p.size }
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(state, Modifier.fillMaxSize()) { i ->
                    AsyncImage(p[i], null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                }
                Text("${state.currentPage + 1} / ${p.size}", Modifier.align(Alignment.BottomCenter).padding(8.dp))
            }
        }
    }
}

@Composable
fun EpubReader(file: File) {
    val ctx = LocalContext.current
    var chapters by remember { mutableStateOf<List<File>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(ctx.cacheDir, "reader/${file.name}_${file.lastModified()}")
                if (!dir.exists()) ArchiveManager.extract(file, dir)
                val container = File(dir, "META-INF/container.xml").readText()
                val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)!!.groupValues[1]
                val opf = File(dir, opfPath)
                val text = opf.readText()
                val manifest = Regex("<item\\b[^>]*>").findAll(text).mapNotNull { m ->
                    val tag = m.value
                    val id = Regex("\\bid=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)
                    val href = Regex("\\bhref=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)
                    if (id != null && href != null) id to href else null
                }.toMap()
                Regex("<itemref\\b[^>]*idref=\"([^\"]+)\"").findAll(text)
                    .mapNotNull { manifest[it.groupValues[1]] }
                    .map { File(opf.parentFile, URLDecoder.decode(it, "UTF-8")) }
                    .filter { it.exists() }.toList()
            }.onSuccess { chapters = it }.onFailure { err = it.message }
        }
    }
    val c = chapters
    when {
        err != null -> Text("Errore: $err", Modifier.padding(16.dp))
        c == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        c.isEmpty() -> Text("Nessun capitolo trovato", Modifier.padding(16.dp))
        else -> Column(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { WebView(it).apply { settings.allowFileAccess = true; settings.javaScriptEnabled = false } },
                update = { it.loadUrl("file://" + c[idx.coerceIn(0, c.lastIndex)].absolutePath) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ if (idx > 0) idx-- }, enabled = idx > 0) { Text("Indietro") }
                Text("${idx + 1} / ${c.size}")
                OutlinedButton({ if (idx < c.lastIndex) idx++ }, enabled = idx < c.lastIndex) { Text("Avanti") }
            }
        }
    }
}
