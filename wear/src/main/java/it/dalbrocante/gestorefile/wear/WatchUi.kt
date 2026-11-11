package it.dalbrocante.gestorefile.wear

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition

private val Green = Color(0xFF065F46)
private val Card = Color(0xFF1F2937)
private val Danger = Color(0xFFF87171)

private fun emoji(r: Row): String = when (r.kind) {
    Kind.DIR -> "📁"
    Kind.HOME -> "📂"
    Kind.FILE -> when (r.title.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp" -> "🖼"
        "mp4", "mkv", "avi", "mov", "webm", "3gp" -> "🎞"
        "mp3", "wav", "ogg", "flac", "m4a", "aac" -> "🎵"
        "pdf" -> "📕"
        "zip", "rar", "7z", "tar", "gz" -> "🗜"
        "apk" -> "🤖"
        "txt", "md", "json", "xml", "csv", "log" -> "📝"
        else -> "📄"
    }
    Kind.ACTION -> if (r.act == Act.UP) "⬆" else "▸"
    Kind.DANGER -> "🗑"
    Kind.INFO -> ""
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WatchApp(vm: WatchVm) {
    MaterialTheme {
        val rows = vm.rows
        val state = rememberLazyListState()
        val focus = remember { FocusRequester() }
        val haptic = LocalHapticFeedback.current
        LaunchedEffect(Unit) { focus.requestFocus() }
        LaunchedEffect(vm.cursor, vm.menu, vm.dir) {
            // l'header è l'elemento 0: il cursore sta a cursor+1; lo teniamo in vista
            if (rows.isNotEmpty()) state.animateScrollToItem((vm.cursor).coerceAtLeast(0))
        }
        Scaffold(timeText = { TimeText() }, vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }) {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = state,
                    modifier = Modifier
                        .fillMaxSize()
                        .onRotaryScrollEvent { e ->
                            if (vm.rotate(e.verticalScrollPixels)) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            true
                        }
                        .focusRequester(focus)
                        .focusable(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 30.dp, bottom = 44.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item(key = "header") {
                        val title = when {
                            vm.menu != null -> "Azioni"
                            vm.dir == null -> "Gestore file"
                            vm.selection.isNotEmpty() -> "✓ ${vm.selection.size} selezionati"
                            else -> vm.dir!!.name.ifEmpty { "/" }
                        }
                        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6EE7B7), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    itemsIndexed(rows, key = { _, r -> r.id }) { i, r ->
                        RowView(r, i == vm.cursor, r.file?.absolutePath in vm.selection && r.file != null,
                            onClick = { vm.cursor = i; vm.activate(r) },
                            onLong = { vm.cursor = i; vm.longPress(r) })
                    }
                }
                vm.message?.let {
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xE6000000)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                        Text(it, fontSize = 12.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowView(r: Row, cursor: Boolean, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val bg = when {
        selected -> Green
        else -> Card
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .then(if (cursor) Modifier.border(2.dp, Color(0xFF34D399), shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val e = emoji(r)
        if (e.isNotEmpty()) { Text(e, fontSize = 16.sp); Spacer(Modifier.width(8.dp)) }
        Column(Modifier.weight(1f)) {
            Text(
                r.title, fontSize = 13.sp, maxLines = if (r.kind == Kind.INFO) 3 else 1, overflow = TextOverflow.Ellipsis,
                color = if (r.kind == Kind.DANGER) Danger else Color.White,
                fontWeight = if (cursor) FontWeight.Bold else FontWeight.Normal,
            )
            if (r.sub.isNotEmpty()) Text(r.sub, fontSize = 10.sp, color = Color(0xFF9CA3AF), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Text("✓", fontSize = 14.sp, color = Color(0xFF6EE7B7))
    }
}
