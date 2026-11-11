package it.dalbrocante.gestorefile.shared

import java.io.File

/** Operazioni sui file locali, usate dall'orologio (e dal telefono per l'invio). */
object LocalOps {
    fun list(dir: File, showHidden: Boolean): List<Entry> =
        (dir.listFiles() ?: emptyArray())
            .filter { showHidden || !it.name.startsWith(".") }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .map { Entry(it.name, it.absolutePath, it.isDirectory, if (it.isFile) it.length() else 0L, it.lastModified()) }

    fun delete(f: File): Boolean = if (f.isDirectory) f.deleteRecursively() else f.delete()

    fun mkdir(parent: File, name: String): String? {
        if (name.isBlank() || name.contains('/')) return "Nome non valido"
        val d = uniqueFile(File(parent, name))
        return if (d.mkdirs()) null else "Impossibile creare la cartella"
    }

    fun rename(f: File, name: String): String? {
        if (name.isBlank() || name.contains('/')) return "Nome non valido"
        val t = File(f.parentFile, name)
        if (t.exists()) return "Esiste già un elemento con questo nome"
        return if (f.renameTo(t)) null else "Rinomina non riuscita"
    }

    fun copy(src: File, dstDir: File): String? = runCatching {
        if (src.isDirectory && dstDir.canonicalPath.startsWith(src.canonicalPath)) return "Non puoi copiare una cartella dentro sé stessa"
        val target = uniqueFile(File(dstDir, src.name))
        if (src.isDirectory) src.copyRecursively(target, overwrite = false) else src.copyTo(target)
        null
    }.getOrElse { it.message ?: "Copia non riuscita" }

    fun move(src: File, dstDir: File): String? = runCatching {
        if (src.parentFile?.canonicalPath == dstDir.canonicalPath) return null
        if (src.isDirectory && dstDir.canonicalPath.startsWith(src.canonicalPath)) return "Non puoi spostare una cartella dentro sé stessa"
        val target = uniqueFile(File(dstDir, src.name))
        if (src.renameTo(target)) null else {
            (if (src.isDirectory) src.copyRecursively(target) else { src.copyTo(target); true }).let { ok ->
                if (ok) { delete(src); null } else "Spostamento non riuscito"
            }
        }
    }.getOrElse { it.message ?: "Spostamento non riuscito" }

    /** Elenco piatto dei file (con percorso relativo al padre dell'elemento radice), per l'invio di cartelle. */
    fun flatten(roots: List<File>): List<Pair<File, String>> {
        val out = ArrayList<Pair<File, String>>()
        for (r in roots) {
            val base = r.parentFile ?: continue
            if (r.isFile) out += r to r.name
            else r.walkTopDown().filter { it.isFile }.forEach { out += it to it.relativeTo(base).path }
        }
        return out
    }
}
