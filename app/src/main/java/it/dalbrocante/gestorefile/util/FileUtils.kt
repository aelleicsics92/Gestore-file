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

/** Apre il file con l'app di sistema adatta. */
fun openExternal(ctx: Context, file: File) {
    val i = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uriFor(ctx, file), mimeOf(file))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ctx.startActivity(Intent.createChooser(i, "Apri con").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Condivisione generica: il chooser di Android include mail, WhatsApp, Dropbox, Facebook, Instagram, Bluetooth, Quick Share/Wi-Fi Direct. */
fun shareFiles(ctx: Context, files: List<File>) {
    val uris = ArrayList(files.filter { it.isFile }.map { uriFor(ctx, it) })
    if (uris.isEmpty()) return
    val i = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(files.first { it.isFile })
            putExtra(Intent.EXTRA_STREAM, uris[0])
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
    }
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Condividi").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
