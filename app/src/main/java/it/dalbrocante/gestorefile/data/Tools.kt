package it.dalbrocante.gestorefile.data

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.security.MessageDigest

object Tools {

    /** Cartelle di primo livello con dimensione, per l'analizzatore spazio. */
    fun usage(root: File): List<Pair<File, Long>> =
        (root.listFiles() ?: emptyArray()).map { it to if (it.isDirectory) it.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } else it.length() }
            .sortedByDescending { it.second }

    /** File più grandi nella cartella. */
    fun biggest(root: File, n: Int = 50): List<File> =
        root.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isFile }.sortedByDescending { it.length() }.take(n).toList()

    /** Duplicati: stessa dimensione, poi stesso SHA-256. */
    fun duplicates(root: File): List<List<File>> {
        val bySize = root.walkTopDown().onEnter { !it.name.startsWith(".") }
            .filter { it.isFile && it.length() > 0 }.groupBy { it.length() }.filter { it.value.size > 1 }
        val out = ArrayList<List<File>>()
        for ((_, group) in bySize) {
            group.groupBy { sha256(it) }.values.filter { it.size > 1 }.forEach { out += it }
        }
        return out
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            var n = s.read(buf)
            while (n > 0) { md.update(buf, 0, n); n = s.read(buf) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Rimuove geolocalizzazione EXIF da un JPEG. */
    fun stripGps(f: File) {
        val e = ExifInterface(f.absolutePath)
        listOf(
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ).forEach { e.setAttribute(it, null) }
        e.saveAttributes()
    }
}

/** Regola di smistamento: file in `watch` con estensione in `exts` finiscono in `target`, opzionale prefisso data. */
data class AutoRule(val watch: File, val exts: Set<String>, val target: File, val datePrefix: Boolean)

object AutoOrganizer {
    fun run(rules: List<AutoRule>, repo: FileRepository): Int {
        var moved = 0
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ITALY).format(java.util.Date())
        for (r in rules) {
            r.watch.listFiles()?.filter { it.isFile && it.extension.lowercase() in r.exts }?.forEach { f ->
                r.target.mkdirs()
                val res = repo.move(f, r.target)
                res.getOrNull()?.let { m ->
                    moved++
                    if (r.datePrefix) m.renameTo(File(m.parentFile, "$today ${m.name}"))
                }
            }
        }
        return moved
    }
}

/** Vault: cartella privata dell'app, non visibile alla galleria. L'accesso richiede biometria (vedi VaultScreen). */
class Vault(private val dir: File) {
    init { dir.mkdirs() }
    fun items(): List<File> = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
    fun add(f: File, repo: FileRepository) = repo.move(f, dir)
    fun restore(f: File, dest: File, repo: FileRepository) = repo.move(f, dest)
}
