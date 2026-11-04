package it.dalbrocante.gestorefile.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.text.DateFormat
import java.util.Date

val ARCHIVE_EXT = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "cbz", "cbr", "jar")
val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "flv", "wmv", "ts")
val AUDIO_EXT = setOf("mp3", "wav", "ogg", "flac", "m4a", "aac", "opus")
val TEXT_EXT = setOf("txt", "json", "xml", "log", "py", "md", "csv", "html", "css", "js", "kt", "java", "php", "sql", "ini", "conf", "yml", "yaml")

fun mimeOf(file: File): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}

private val dateFmt = object : ThreadLocal<DateFormat>() {
    override fun initialValue(): DateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
}

fun formatDate(ms: Long): String = dateFmt.get()!!.format(Date(ms))

fun uriFor(ctx: Context, file: File): Uri =
    FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)

private fun grantAll(ctx: Context, intent: Intent, uris: List<Uri>) {
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // Concede esplicitamente il permesso a ogni app che può ricevere l'intent (WhatsApp, Telegram, ecc.)
    val pm = ctx.packageManager
    pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).forEach { ri ->
        uris.forEach { ctx.grantUriPermission(ri.activityInfo.packageName, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}

private fun launch(ctx: Context, intent: Intent, title: String) {
    try {
        ctx.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(ctx, "Nessuna app disponibile", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** Apre il file con l'app di sistema adatta. */
fun openExternal(ctx: Context, file: File) {
    val uri = uriFor(ctx, file)
    val i = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeOf(file))
        clipData = android.content.ClipData.newRawUri(file.name, uri)
    }
    grantAll(ctx, i, listOf(uri))
    launch(ctx, i, "Apri con")
}

private fun commonMime(files: List<File>): String {
    val mimes = files.map { mimeOf(it) }.distinct()
    return when {
        mimes.size == 1 -> mimes[0]
        mimes.all { it.startsWith("image/") } -> "image/*"
        mimes.all { it.startsWith("video/") } -> "video/*"
        mimes.all { it.startsWith("audio/") } -> "audio/*"
        mimes.all { it.startsWith("text/") } -> "text/*"
        else -> "*/*"
    }
}

/**
 * Condivisione con FileProvider (content://) + ClipData + FLAG_GRANT_READ_URI_PERMISSION:
 * accettata da WhatsApp, Telegram, Gmail, Drive ecc. Le cartelle vengono prima compresse in ZIP.
 */
fun shareFiles(ctx: Context, files: List<File>) {
    if (files.isEmpty()) return
    if (files.any { it.isDirectory }) {
        android.widget.Toast.makeText(ctx, "Preparo lo ZIP…", android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            val prepared = runCatching {
                val dir = File(ctx.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
                files.map { f ->
                    if (!f.isDirectory) f else File(dir, f.name + ".zip").also { z ->
                        java.util.zip.ZipOutputStream(z.outputStream().buffered()).use { zo ->
                            val base = f.parentFile ?: f
                            f.walkTopDown().filter { it.isFile }.forEach { src ->
                                zo.putNextEntry(java.util.zip.ZipEntry(src.relativeTo(base).path.replace(File.separatorChar, '/')))
                                src.inputStream().use { it.copyTo(zo) }
                                zo.closeEntry()
                            }
                        }
                    }
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                prepared.onSuccess { shareFiles(ctx, it) }
                    .onFailure { android.widget.Toast.makeText(ctx, "ZIP fallito: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
            }
        }.start()
        return
    }
    val uris = files.map { uriFor(ctx, it) }
    val i = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
    }
    i.type = commonMime(files)
    val clip = android.content.ClipData.newRawUri(files[0].name, uris[0])
    uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
    i.clipData = clip
    grantAll(ctx, i, uris)
    launch(ctx, i, "Condividi")
}
