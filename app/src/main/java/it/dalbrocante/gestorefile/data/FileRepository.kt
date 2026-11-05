package it.dalbrocante.gestorefile.data

import java.io.File
import java.util.concurrent.TimeUnit

enum class SortMode { AUTO, NAME, DATE, SIZE, TYPE }

data class FileItem(
    val file: File,
    val name: String = file.name,
    val isDir: Boolean = file.isDirectory,
    val size: Long = if (file.isFile) file.length() else 0L,
    val modified: Long = file.lastModified(),
    val ext: String = file.extension.lowercase(),
)

data class SearchFilter(
    val query: String = "",
    val extensions: Set<String> = emptySet(),
    val minSize: Long = 0L,
    val modifiedAfter: Long = 0L,
)

data class TrashEntry(val file: File, val name: String, val deletedAt: Long, val size: Long, val isDir: Boolean, val daysLeft: Int)

class FileRepository(private val trashDir: File) {

    init {
        trashDir.mkdirs()
        purgeTrash()
    }

    private fun statItem(f: File): FileItem {
        val a = try {
            java.nio.file.Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        } catch (e: Exception) { null }
        return if (a != null) {
            FileItem(f, f.name, a.isDirectory, if (a.isDirectory) 0L else a.size(), a.lastModifiedTime().toMillis(), f.name.substringAfterLast('.', "").lowercase())
        } else FileItem(f)
    }

    /** Elenco cartella: le letture degli attributi (lente su storage FUSE) sono eseguite in parallelo. */
    fun list(dir: File, sort: SortMode = SortMode.NAME, showHidden: Boolean = false): List<FileItem> {
        val files = dir.listFiles() ?: return emptyList()
        val visible = if (showHidden) files.asList() else files.filter { !it.name.startsWith(".") }
        val items: MutableList<FileItem> =
            if (visible.size < 200) visible.mapTo(ArrayList(visible.size)) { statItem(it) }
            else visible.parallelStream().map { statItem(it) }.collect(java.util.stream.Collectors.toList())
        val byName = compareBy<FileItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        // AUTO: più recenti prima in Download e nelle cartelle di foto/video, altrimenti per nome
        val effective = if (sort != SortMode.AUTO) sort else {
            val media = items.count { !it.isDir && (it.ext in it.dalbrocante.gestorefile.util.IMAGE_EXT || it.ext in it.dalbrocante.gestorefile.util.VIDEO_EXT) }
            val files = items.count { !it.isDir }
            if (dir.name.equals("Download", true) || dir.name.equals("Downloads", true) || (files > 0 && media * 2 > files)) SortMode.DATE else SortMode.NAME
        }
        val cmp: Comparator<FileItem> = when (effective) {
            SortMode.AUTO, SortMode.NAME -> byName
            SortMode.DATE -> compareByDescending { it.modified }
            SortMode.SIZE -> compareByDescending { it.size }
            SortMode.TYPE -> compareBy<FileItem> { it.ext }.then(byName)
        }
        items.sortWith(compareByDescending<FileItem> { it.isDir }.then(cmp))
        return items
    }

    fun createFolder(parent: File, name: String): Result<File> = runCatching {
        val f = File(parent, name)
        require(!f.exists()) { "Esiste già" }
        check(f.mkdirs()) { "Impossibile creare la cartella" }
        f
    }

    fun createFile(parent: File, name: String): Result<File> = runCatching {
        val f = File(parent, name)
        require(!f.exists()) { "Esiste già" }
        check(f.createNewFile()) { "Impossibile creare il file" }
        f
    }

    fun rename(file: File, newName: String): Result<File> = runCatching {
        val target = File(file.parentFile, newName)
        require(!target.exists()) { "Esiste già" }
        check(file.renameTo(target)) { "Rinomina fallita" }
        target
    }

    fun copy(src: File, destDir: File): Result<File> = runCatching {
        val target = uniqueTarget(destDir, src.name)
        if (src.isDirectory) {
            require(!target.canonicalPath.startsWith(src.canonicalPath + File.separator)) {
                "Non puoi copiare una cartella dentro sé stessa"
            }
            src.copyRecursively(target)
        } else {
            src.copyTo(target)
        }
        target
    }

    fun move(src: File, destDir: File): Result<File> = runCatching {
        val target = uniqueTarget(destDir, src.name)
        if (!src.renameTo(target)) {
            if (src.isDirectory) src.copyRecursively(target) else src.copyTo(target)
            check(src.deleteRecursively()) { "Spostamento incompleto" }
        }
        target
    }

    fun deletePermanently(file: File): Boolean = file.deleteRecursively()

    // Cestino: sposta in trashDir con metadati (percorso originale + data eliminazione)
    fun moveToTrash(file: File): Result<File> = runCatching {
        val stamp = System.currentTimeMillis()
        val dest = File(trashDir, "${stamp}__${file.name}")
        if (!file.renameTo(dest)) {
            if (file.isDirectory) file.copyRecursively(dest) else file.copyTo(dest)
            check(file.deleteRecursively())
        }
        File(trashDir, "${stamp}__${file.name}.origin").writeText(file.absolutePath)
        dest
    }

    fun trashItems(): List<FileItem> =
        trashDir.listFiles()?.filter { !it.name.endsWith(".origin") }?.map { FileItem(it) }
            ?.sortedByDescending { it.modified } ?: emptyList()

    fun restoreFromTrash(item: File): Result<File> = runCatching {
        val origin = File(trashDir, item.name + ".origin")
        val originalPath = if (origin.exists()) origin.readText() else error("Origine sconosciuta")
        val original = File(originalPath)
        original.parentFile?.mkdirs()
        val target = uniqueTarget(original.parentFile!!, original.name)
        check(item.renameTo(target)) { "Ripristino fallito" }
        origin.delete()
        target
    }

    fun trashEntries(retentionDays: Int = 30): List<TrashEntry> {
        val now = System.currentTimeMillis()
        return (trashDir.listFiles() ?: emptyArray()).filter { !it.name.endsWith(".origin") }.map { f ->
            val stamp = f.name.substringBefore("__").toLongOrNull() ?: f.lastModified()
            val left = (retentionDays - TimeUnit.MILLISECONDS.toDays(now - stamp)).toInt().coerceAtLeast(0)
            val size = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()
            TrashEntry(f, f.name.substringAfter("__"), stamp, size, f.isDirectory, left)
        }.sortedByDescending { it.deletedAt }
    }

    /** Spazio esatto occupato dal cestino (file + cartelle). */
    fun trashSize(): Long = trashEntries().sumOf { it.size }

    fun deleteFromTrash(item: File) {
        File(trashDir, item.name + ".origin").delete()
        item.deleteRecursively()
    }

    fun emptyTrash() {
        trashDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun purgeTrash(days: Long = 30) {
        val limit = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days)
        trashDir.listFiles()?.forEach {
            val stamp = it.name.substringBefore("__").toLongOrNull()
            if (stamp != null && stamp < limit) it.deleteRecursively()
        }
    }

    fun search(root: File, filter: SearchFilter, limit: Int = 500): List<FileItem> {
        val out = ArrayList<FileItem>()
        val q = filter.query.lowercase()
        root.walkTopDown()
            .onEnter { !it.name.startsWith(".") }
            .forEach { f ->
                if (out.size >= limit) return@forEach
                if (f == root) return@forEach
                if (q.isNotEmpty() && !f.name.lowercase().contains(q)) return@forEach
                if (f.isFile) {
                    if (filter.extensions.isNotEmpty() && f.extension.lowercase() !in filter.extensions) return@forEach
                    if (f.length() < filter.minSize) return@forEach
                } else if (filter.extensions.isNotEmpty() || filter.minSize > 0) {
                    return@forEach
                }
                if (f.lastModified() < filter.modifiedAfter) return@forEach
                out += FileItem(f)
            }
        return out
    }

    fun folderSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun uniqueTarget(dir: File, name: String): File {
        var t = File(dir, name)
        if (!t.exists()) return t
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (t.exists()) {
            t = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return t
    }
}
