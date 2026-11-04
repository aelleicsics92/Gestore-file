package it.dalbrocante.gestorefile.data

import com.github.junrar.Archive
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

data class ArchiveEntryInfo(val path: String, val isDir: Boolean, val size: Long)

object ArchiveManager {

    private fun kind(f: File): String {
        val n = f.name.lowercase()
        return when {
            n.endsWith(".zip") || n.endsWith(".cbz") || n.endsWith(".jar") || n.endsWith(".epub") -> "zip"
            n.endsWith(".rar") || n.endsWith(".cbr") -> "rar"
            n.endsWith(".7z") -> "7z"
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> "tgz"
            n.endsWith(".tar.bz2") -> "tbz2"
            n.endsWith(".tar.xz") -> "txz"
            n.endsWith(".tar") -> "tar"
            n.endsWith(".gz") -> "gz"
            n.endsWith(".bz2") -> "bz2"
            n.endsWith(".xz") -> "xz"
            else -> "unknown"
        }
    }

    fun isEncrypted(f: File): Boolean = runCatching {
        when (kind(f)) {
            "zip" -> ZipFile(f).isEncrypted
            "rar" -> Archive(f).use { it.isEncrypted }
            else -> false
        }
    }.getOrDefault(false)

    /** Elenca il contenuto senza estrarre. */
    fun list(f: File, password: String? = null): List<ArchiveEntryInfo> = when (kind(f)) {
        "zip" -> ZipFile(f, password?.toCharArray()).fileHeaders
            .map { ArchiveEntryInfo(it.fileName, it.isDirectory, it.uncompressedSize) }
        "rar" -> Archive(f, password).use { a ->
            a.fileHeaders.map { ArchiveEntryInfo(it.fileName.replace('\\', '/'), it.isDirectory, it.fullUnpackSize) }
        }
        "7z" -> SevenZFile.builder().setFile(f).apply { password?.let { setPassword(it.toCharArray()) } }.get().use { s ->
            generateSequence { s.nextEntry }.map { ArchiveEntryInfo(it.name, it.isDirectory, it.size) }.toList()
        }
        "tar", "tgz", "tbz2", "txz" -> tarStream(f).use { ais ->
            generateSequence { ais.nextEntry }.map { ArchiveEntryInfo(it.name, it.isDirectory, it.size) }.toList()
        }
        else -> listOf(ArchiveEntryInfo(f.nameWithoutExtension, false, f.length()))
    }

    /** True se la voce appartiene a onlyEntry (file esatto oppure contenuto di una cartella). */
    private fun hit(name: String, only: String?): Boolean {
        if (only == null) return true
        val o = only.trimEnd('/')
        val n = name.trimEnd('/')
        return n == o || n.startsWith("$o/")
    }

    /** Nome di uscita: con flat, un singolo file viene estratto direttamente in dest (senza sottocartelle). */
    private fun outName(name: String, only: String?, flat: Boolean) =
        if (flat && only != null && name == only) name.substringAfterLast('/') else name

    /** Estrae tutto (o una singola voce / cartella) in dest, senza decomprimere il resto dell'archivio. */
    fun extract(f: File, dest: File, password: String? = null, onlyEntry: String? = null, flat: Boolean = false) {
        dest.mkdirs()
        when (kind(f)) {
            "zip" -> ZipFile(f, password?.toCharArray()).let { z ->
                if (onlyEntry == null) z.extractAll(dest.absolutePath)
                else z.fileHeaders.filter { hit(it.fileName, onlyEntry) }.forEach { h ->
                    val nn = outName(h.fileName, onlyEntry, flat)
                    if (nn != h.fileName) z.extractFile(h, dest.absolutePath, nn) else z.extractFile(h, dest.absolutePath)
                }
            }
            "rar" -> Archive(f, password).use { a ->
                for (h in a.fileHeaders) {
                    val name = h.fileName.replace('\\', '/')
                    if (!hit(name, onlyEntry)) continue
                    val out = safeChild(dest, outName(name, onlyEntry, flat))
                    if (h.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { a.extractFile(h, it) }
                    }
                }
            }
            "7z" -> SevenZFile.builder().setFile(f).apply { password?.let { setPassword(it.toCharArray()) } }.get().use { s ->
                var e = s.nextEntry
                while (e != null) {
                    if (hit(e.name, onlyEntry)) {
                        val out = safeChild(dest, outName(e.name, onlyEntry, flat))
                        if (e.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { o ->
                                val buf = ByteArray(64 * 1024)
                                var n = s.read(buf)
                                while (n > 0) { o.write(buf, 0, n); n = s.read(buf) }
                            }
                        }
                    }
                    e = s.nextEntry
                }
            }
            "tar", "tgz", "tbz2", "txz" -> tarStream(f).use { ais ->
                var e = ais.nextEntry
                while (e != null) {
                    if (hit(e.name, onlyEntry)) {
                        val out = safeChild(dest, outName(e.name, onlyEntry, flat))
                        if (e.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { ais.copyTo(it) }
                        }
                    }
                    e = ais.nextEntry
                }
            }
            "gz", "bz2", "xz" -> {
                val alg = when (kind(f)) { "gz" -> CompressorStreamFactory.GZIP; "bz2" -> CompressorStreamFactory.BZIP2; else -> CompressorStreamFactory.XZ }
                val out = File(dest, f.nameWithoutExtension)
                BufferedInputStream(FileInputStream(f)).use { fin ->
                    CompressorStreamFactory().createCompressorInputStream(alg, fin).use { cin ->
                        out.outputStream().use { cin.copyTo(it) }
                    }
                }
            }
            else -> error("Formato non supportato")
        }
    }

    /** Crea ZIP con livello di compressione (1..9) e password AES opzionale. */
    fun createZip(sources: List<File>, target: File, level: Int = 5, password: String? = null) {
        val params = ZipParameters().apply {
            compressionLevel = when {
                level <= 1 -> CompressionLevel.FASTEST
                level <= 3 -> CompressionLevel.FAST
                level <= 6 -> CompressionLevel.NORMAL
                level <= 8 -> CompressionLevel.MAXIMUM
                else -> CompressionLevel.ULTRA
            }
            if (!password.isNullOrEmpty()) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }
        val zip = if (password.isNullOrEmpty()) ZipFile(target) else ZipFile(target, password.toCharArray())
        sources.forEach { if (it.isDirectory) zip.addFolder(it, params) else zip.addFile(it, params) }
    }

    /** Crea TAR.GZ. */
    fun createTarGz(sources: List<File>, target: File) {
        target.outputStream().buffered().use { fos ->
            val gz = CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.GZIP, fos)
            val tar = org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(gz)
            tar.setLongFileMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_POSIX)
            fun add(f: File, base: String) {
                val name = base + f.name
                tar.putArchiveEntry(tar.createArchiveEntry(f, name))
                if (f.isFile) f.inputStream().use { it.copyTo(tar) }
                tar.closeArchiveEntry()
                if (f.isDirectory) f.listFiles()?.forEach { add(it, "$name/") }
            }
            sources.forEach { add(it, "") }
            tar.finish(); tar.close(); gz.close()
        }
    }

    private fun tarStream(f: File): org.apache.commons.compress.archivers.tar.TarArchiveInputStream {
        val base: InputStream = BufferedInputStream(FileInputStream(f))
        val wrapped: InputStream = when (kind(f)) {
            "tgz" -> CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.GZIP, base)
            "tbz2" -> CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.BZIP2, base)
            "txz" -> CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.XZ, base)
            else -> base
        }
        return org.apache.commons.compress.archivers.tar.TarArchiveInputStream(BufferedInputStream(wrapped))
    }

    /** Protezione zip-slip. */
    private fun safeChild(dest: File, name: String): File {
        val out = File(dest, name)
        require(out.canonicalPath.startsWith(dest.canonicalPath + File.separator) || out.canonicalPath == dest.canonicalPath) {
            "Percorso non valido nell'archivio"
        }
        return out
    }
}
