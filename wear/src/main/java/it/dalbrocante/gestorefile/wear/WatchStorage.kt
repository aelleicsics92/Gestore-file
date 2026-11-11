package it.dalbrocante.gestorefile.wear

import android.content.Context
import android.os.Environment
import java.text.DateFormat
import java.util.Date

object WatchStorage {
    fun hasAll(): Boolean = Environment.isExternalStorageManager()

    /** Radice visibile: tutta la memoria se concesso "tutti i file", altrimenti la cartella dell'app. */
    fun root(ctx: Context): java.io.File =
        if (hasAll()) Environment.getExternalStorageDirectory() else (ctx.getExternalFilesDir(null) ?: ctx.filesDir)

    fun receiveDir(ctx: Context): java.io.File {
        val base = if (hasAll()) java.io.File(root(ctx), "Download") else root(ctx)
        return java.io.File(base, "Telefono").also { it.mkdirs() }
    }
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}

fun formatDate(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
