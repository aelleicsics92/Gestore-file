package it.dalbrocante.gestorefile.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import it.dalbrocante.gestorefile.util.AUDIO_EXT
import it.dalbrocante.gestorefile.util.IMAGE_EXT
import it.dalbrocante.gestorefile.util.TEXT_EXT
import it.dalbrocante.gestorefile.util.VIDEO_EXT
import it.dalbrocante.gestorefile.util.openExternal
import it.dalbrocante.gestorefile.util.shareFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** True se l'app ha un viewer interno per l'estensione. */
fun hasInternalViewer(ext: String) =
    ext in IMAGE_EXT || ext in VIDEO_EXT || ext in AUDIO_EXT || ext in TEXT_EXT || ext == "pdf" || ext == "epub" || ext == "cbz" || ext == "cbr"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(file: File, siblings: List<File>?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val ext = file.extension.lowercase()
    val sibs by produceState<List<File>?>(siblings, file) {
        if (siblings == null && file.extension.lowercase() in IMAGE_EXT) {
            value = withContext(Dispatchers.IO) {
                file.parentFile?.listFiles()?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXT }
                    ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            }
        }
    }
    val gallery = remember(sibs, ext) { sibs?.filter { it.extension.lowercase() in IMAGE_EXT }?.ifEmpty { null } ?: listOf(file) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(file.name, maxLines = 1) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
            actions = {
                IconButton({ shareFiles(ctx, listOf(file)) }) { Icon(Icons.Default.Share, "Condividi") }
                IconButton({ openExternal(ctx, file) }) { Icon(Icons.Default.OpenInNew, "Apri con") }
            },
        )
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                ext in IMAGE_EXT -> ImageGallery(file, gallery)
                ext in VIDEO_EXT || ext in AUDIO_EXT -> MediaPlayer(file)
                ext == "pdf" -> PdfViewer(file)
                ext == "epub" -> EpubReader(file)
                ext == "cbz" || ext == "cbr" -> ComicReader(file)
                ext == "csv" -> CsvViewer(file)
                ext in TEXT_EXT -> TextEditor(file)
                else -> LaunchedEffect(file) { openExternal(ctx, file); onBack() }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageGallery(start: File, images: List<File>) {
    val ctx = LocalContext.current
    val list = images.ifEmpty { listOf(start) }
    val pager = rememberPagerState(initialPage = list.indexOf(start).coerceAtLeast(0)) { list.size }
    val loader = remember {
        ImageLoader.Builder(ctx).components {
            add(if (android.os.Build.VERSION.SDK_INT >= 28) ImageDecoderDecoder.Factory() else GifDecoder.Factory())
            add(VideoFrameDecoder.Factory())
        }.build()
    }
    HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
        var scale by remember { mutableStateOf(1f) }
        var rot by remember { mutableStateOf(0f) }
        var ox by remember { mutableStateOf(0f) }
        var oy by remember { mutableStateOf(0f) }
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(list[page]).build(),
                imageLoader = loader,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            if (scale > 1f) { ox += pan.x; oy += pan.y } else { ox = 0f; oy = 0f }
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, rotationZ = rot, translationX = ox, translationY = oy),
            )
            IconButton({ rot = (rot + 90f) % 360f }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                Icon(Icons.Default.Rotate90DegreesCw, "Ruota")
            }
        }
    }
}

@Composable
private fun MediaPlayer(file: File) {
    val ctx = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            addListener(object : androidx.media3.common.Player.Listener {
                // Se il formato non è supportato dal player integrato, passa a quello di sistema
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { openExternal(ctx, file) }
            })
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file))); prepare(); playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    AndroidView({ PlayerView(it).apply { this.player = player; useController = true } }, Modifier.fillMaxSize())
}

@Composable
private fun PdfViewer(file: File) {
    var pages by remember { mutableStateOf<List<Bitmap>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { r ->
                        val out = ArrayList<Bitmap>()
                        for (i in 0 until r.pageCount) {
                            r.openPage(i).use { p ->
                                val w = 1080
                                val h = (w.toFloat() / p.width * p.height).toInt()
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                out += bmp
                            }
                        }
                        out
                    }
                }
            }.onSuccess { pages = it }.onFailure { err = it.message }
        }
    }
    when {
        err != null -> Text("Errore: $err", Modifier.padding(16.dp))
        pages == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(pages!!) { b -> Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().padding(bottom = 4.dp), contentScale = ContentScale.FillWidth) }
        }
    }
}

@Composable
private fun CsvViewer(file: File) {
    var rows by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    LaunchedEffect(file) {
        rows = withContext(Dispatchers.IO) {
            val sep = if (file.useLines { it.firstOrNull()?.count { c -> c == ';' } ?: 0 } > 0) ';' else ','
            file.useLines { ls -> ls.take(2000).map { it.split(sep) }.toList() }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column {
                rows.forEachIndexed { i, r ->
                    Row {
                        r.forEach { c ->
                            Text(
                                c,
                                Modifier.padding(horizontal = 8.dp, vertical = 4.dp).then(Modifier),
                                style = if (i == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextEditor(file: File) {
    var text by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    LaunchedEffect(file) { text = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrDefault("") } }
    if (text == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ file.writeText(text!!); dirty = false }, enabled = dirty) { Icon(Icons.Default.Save, "Salva") }
            if (dirty) Text("Modificato", Modifier.padding(start = 8.dp))
        }
        androidx.compose.foundation.text.BasicTextField(
            value = text!!,
            onValueChange = { text = it; dirty = true },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onBackground),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxSize().padding(8.dp).verticalScroll(rememberScrollState()),
        )
    }
}
