package it.dalbrocante.gestorefile.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.size.Precision
import it.dalbrocante.gestorefile.data.FileItem
import it.dalbrocante.gestorefile.util.ARCHIVE_EXT
import it.dalbrocante.gestorefile.util.AUDIO_EXT
import it.dalbrocante.gestorefile.util.IMAGE_EXT
import it.dalbrocante.gestorefile.util.TEXT_EXT
import it.dalbrocante.gestorefile.util.VIDEO_EXT
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

// ---------- Tipi di file: icona e colore ----------

data class FileKind(val icon: ImageVector, val color: Color, val label: String)

private val CODE_EXT = setOf("py", "js", "kt", "java", "php", "html", "css", "sql", "json", "xml", "yml", "yaml", "sh", "c", "cpp", "h", "ts")

fun kindOf(ext: String, isDir: Boolean): FileKind = when {
    isDir -> FileKind(Icons.Filled.Folder, Color(0xFFFFB020), "Cartelle")
    ext == "pdf" -> FileKind(Icons.Filled.PictureAsPdf, Color(0xFFE5484D), "PDF")
    ext in setOf("xls", "xlsx", "ods", "csv") -> FileKind(Icons.Filled.TableChart, Color(0xFF2FA866), "Fogli di calcolo")
    ext in setOf("doc", "docx", "odt", "rtf") -> FileKind(Icons.Filled.Description, Color(0xFF3B82F6), "Documenti")
    ext in setOf("ppt", "pptx", "odp") -> FileKind(Icons.Filled.Slideshow, Color(0xFFFB7A3D), "Presentazioni")
    ext in ARCHIVE_EXT -> FileKind(Icons.Filled.Inventory2, Color(0xFF8B5CF6), "Archivi")
    ext in CODE_EXT -> FileKind(Icons.Filled.Code, Color(0xFFFF8A3D), "Codice")
    ext in TEXT_EXT || ext == "md" -> FileKind(Icons.Filled.TextSnippet, Color(0xFF64748B), "Testo")
    ext in AUDIO_EXT -> FileKind(Icons.Filled.MusicNote, Color(0xFFEC4899), "Audio")
    ext in VIDEO_EXT -> FileKind(Icons.Filled.Movie, Color(0xFF06B6D4), "Video")
    ext in IMAGE_EXT -> FileKind(Icons.Filled.Image, Color(0xFF0EA5E9), "Immagini")
    ext == "apk" -> FileKind(Icons.Filled.Android, Color(0xFF14B8A6), "App")
    ext in setOf("epub", "cbz", "cbr", "mobi") -> FileKind(Icons.Filled.MenuBook, Color(0xFFD97706), "Libri")
    else -> FileKind(Icons.Filled.InsertDriveFile, Color(0xFF94A3B8), "Altro")
}

/** Categoria per le statistiche (nome, colore). */
fun categoryOf(ext: String): FileKind = kindOf(ext, false)

// ---------- Miniature ----------

object Thumbs {
    @Volatile private var loader: ImageLoader? = null

    fun loader(ctx: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(ctx.applicationContext)
            .components {
                add(if (android.os.Build.VERSION.SDK_INT >= 28) ImageDecoderDecoder.Factory() else GifDecoder.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build().also { loader = it }
    }
}

@Composable
fun FileThumb(item: FileItem, modifier: Modifier = Modifier, size: Dp = 56.dp, fill: Boolean = false, neutral: Boolean = false) {
    val ctx = LocalContext.current
    val kind = remember(item.ext, item.isDir) { kindOf(item.ext, item.isDir) }
    val isVideo = !item.isDir && item.ext in VIDEO_EXT
    val hasPreview = !item.isDir && (item.ext in IMAGE_EXT || isVideo)
    Box(
        (if (fill) modifier else modifier.size(size)).clip(RoundedCornerShape(if (fill) 0.dp else 16.dp)).background(if (neutral) MaterialTheme.colorScheme.surfaceContainerHigh else kind.color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        // L'icona resta sotto: è il segnaposto finché la miniatura non è pronta
        if (item.ext.isNotEmpty() && !item.isDir && kind.label == "Altro") {
            Text(item.ext.take(4).uppercase(), color = kind.color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        } else {
            Icon(kind.icon, null, tint = kind.color, modifier = Modifier.size(if (fill) 48.dp else size * 0.5f))
        }
        if (hasPreview) {
            val req = remember(item.file.absolutePath, item.modified) {
                ImageRequest.Builder(ctx).data(item.file)
                    .size(coil.size.Size(200, 200)).precision(Precision.INEXACT)
                    .memoryCacheKey("${item.file.absolutePath}@${item.modified}")
                    .build()
            }
            AsyncImage(req, null, imageLoader = Thumbs.loader(ctx), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (isVideo) {
                Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(18.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}

// ---------- Tile e sezioni ----------

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun ToolTile(icon: ImageVector, label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

data class Tile(val icon: ImageVector, val label: String, val color: Color, val onClick: () -> Unit)

/** Griglia semplice di tile a N colonne, per bottom sheet e dialog. */
@Composable
fun TileGrid(tiles: List<Tile>, columns: Int = 3) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t -> ToolTile(t.icon, t.label, t.color, Modifier.weight(1f), t.onClick) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ---------- Grafico a ciambella interattivo ----------

data class Slice(val label: String, val value: Long, val color: Color)

@Composable
fun DonutChart(
    slices: List<Slice>, selected: Int?, onSelect: (Int?) -> Unit,
    centerTop: String, centerBottom: String, modifier: Modifier = Modifier,
) {
    val total = slices.sumOf { it.value }.coerceAtLeast(1L).toFloat()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(slices) { progress.snapTo(0f); progress.animateTo(1f, tween(800)) }
    val bg = MaterialTheme.colorScheme.surfaceVariant

    Box(modifier.size(250.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(slices) {
                detectTapGestures { p ->
                    val c = Offset(this.size.width / 2f, this.size.height / 2f)
                    val dx = p.x - c.x; val dy = p.y - c.y
                    val dist = hypot(dx, dy); val outer = min(this.size.width, this.size.height) / 2f
                    if (dist < outer * 0.5f || dist > outer + 12f) { onSelect(null); return@detectTapGestures }
                    var ang = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
                    if (ang < 0) ang += 360f
                    var acc = 0f
                    slices.forEachIndexed { i, s ->
                        val sweep = s.value / total * 360f
                        if (ang >= acc && ang < acc + sweep) { onSelect(if (selected == i) null else i); return@detectTapGestures }
                        acc += sweep
                    }
                }
            },
        ) {
            val thick = this.size.minDimension * 0.17f
            val inset = thick / 2f + 8f
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            drawCircle(bg.copy(alpha = 0.35f), radius = arcSize.width / 2f, center = center, style = Stroke(thick))
            var start = -90f
            slices.forEachIndexed { i, s ->
                val sweep = s.value / total * 360f * progress.value
                val gap = if (slices.size > 1 && sweep > 3f) 2.5f else 0f
                val isSel = selected == i
                val dim = selected != null && !isSel
                drawArc(
                    color = s.color.copy(alpha = if (dim) 0.35f else 1f),
                    startAngle = start + gap / 2f, sweepAngle = (sweep - gap).coerceAtLeast(0.5f), useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize,
                    style = Stroke(width = if (isSel) thick * 1.18f else thick, cap = StrokeCap.Butt),
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerTop, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(centerBottom, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

val ChartPalette = listOf(
    Color(0xFF5B4BDB), Color(0xFF0EA5E9), Color(0xFF2FA866), Color(0xFFFFB020),
    Color(0xFFE5484D), Color(0xFFEC4899), Color(0xFF14B8A6), Color(0xFFFB7A3D),
)


/** Card standard dell'app: stessi bordi arrotondati, padding e colore ovunque. */
@Composable
fun CardItem(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val inner: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
    if (onClick != null) ElevatedCard(onClick, modifier, shape = RoundedCornerShape(20.dp), colors = colors, content = inner)
    else ElevatedCard(modifier, shape = RoundedCornerShape(20.dp), colors = colors, content = inner)
}


/** ListItem con lo stesso aspetto delle card dell'app (bordi arrotondati, padding uniforme). */
@Composable
fun CardListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors? = null,
) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp,
    ) {
        ListItem(
            headlineContent = headlineContent, modifier = modifier, supportingContent = supportingContent,
            leadingContent = leadingContent, trailingContent = trailingContent,
            colors = colors ?: ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}
