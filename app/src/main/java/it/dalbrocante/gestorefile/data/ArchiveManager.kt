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
            n.endsWith(".zip") || n.endsWith(".cbz") || n.endsWith(".jar") -> "zip"
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
            generateSequence<ArchiveEntry> { ais.nextEntry }.map { ArchiveEntryInfo(it.name, it.isDirectory, it.size) }.toList()
        }
        else -> listOf(ArchiveEntryInfo(f.nameWithoutExtension, false, f.length()))
    }

    /** Estrae tutto in dest. */
    fun extract(f: File, dest: File, password: String? = null, onlyEntry: String? = null) {
        dest.mkdirs()
        when (kind(f)) {
            "zip" -> ZipFile(f, password?.toCharArray()).let {
                if (onlyEntry == null) it.extractAll(dest.absolutePath) else it.extractFile(onlyEntry, dest.absolutePath)
            }
            "rar" -> Archive(f, password).use { a ->
                for (h in a.fileHeaders) {
                    val name = h.fileName.replace('\\', '/')
                    if (onlyEntry != null && name != onlyEntry) continue
                    val out = safeChild(dest, name)
                    if (h.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { a.extractFile(h, it) }
                    }
                }
            }
            "7z" -> SevenZFile.builder().setFile(f).apply { password?.let { setPassword(it.toCharArray()) } }.get().use { s ->
                var e = s.nextEntry
                while (e != null) {
                    if (onlyEntry == null || e.name == onlyEntry) {
                        val out = safeChild(dest, e.name)
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
                    if (onlyEntry == null || e.name == onlyEntry) {
                        val out = safeChild(dest, e.name)
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
            val tar = ArchiveStreamFactory().createArchiveOutputStream(ArchiveStreamFactory.TAR, gz)
            (tar as org.apache.commons.compress.archivers.tar.TarArchiveOutputStream)
                .setLongFileMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_POSIX)
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

    private fun tarStream(f: File): org.apache.commons.compress.archivers.ArchiveInputStream<*> {
        val base: InputStream = BufferedInputStream(FileInputStream(f))
        val wrapped: InputStream = when (kind(f)) {
            "tgz" -> CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.GZIP, base)
            "tbz2" -> CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.BZIP2, base)
            "txz" -> CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.XZ, base)
            else -> base
        }
        return ArchiveStreamFactory().createArchiveInputStream(ArchiveStreamFactory.TAR, BufferedInputStream(wrapped))
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
