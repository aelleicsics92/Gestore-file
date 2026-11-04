package it.dalbrocante.gestorefile.data

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.File
import java.util.EnumSet
import java.util.Vector

enum class Protocol { FTP, SFTP, SMB }

data class RemoteEntry(val name: String, val path: String, val isDir: Boolean, val size: Long)

data class RemoteConfig(
    val protocol: Protocol,
    val host: String,
    val port: Int,
    val user: String,
    val pass: String,
    val share: String = "",
)

interface RemoteClient {
    fun list(path: String): List<RemoteEntry>
    fun download(path: String, dest: File)
    fun upload(src: File, path: String)
    fun close()

    companion object {
        fun connect(c: RemoteConfig): RemoteClient = when (c.protocol) {
            Protocol.FTP -> FtpRemote(c)
            Protocol.SFTP -> SftpRemote(c)
            Protocol.SMB -> SmbRemote(c)
        }

        fun join(dir: String, name: String) = when {
            dir.isEmpty() -> name
            dir.endsWith("/") -> dir + name
            else -> "$dir/$name"
        }
    }
}

private class FtpRemote(c: RemoteConfig) : RemoteClient {
    private val ftp = FTPClient()
    init {
        ftp.connectTimeout = 15000
        ftp.connect(c.host, c.port)
        check(ftp.login(c.user.ifEmpty { "anonymous" }, c.pass)) { "Login FTP rifiutato" }
        ftp.enterLocalPassiveMode()
        ftp.setFileType(FTP.BINARY_FILE_TYPE)
    }
    override fun list(path: String) = ftp.listFiles(path.ifEmpty { "/" })
        .filter { it.name != "." && it.name != ".." }
        .map { RemoteEntry(it.name, RemoteClient.join(path.ifEmpty { "/" }, it.name), it.isDirectory, it.size) }
    override fun download(path: String, dest: File) { dest.outputStream().use { check(ftp.retrieveFile(path, it)) { "Download fallito" } } }
    override fun upload(src: File, path: String) { src.inputStream().use { check(ftp.storeFile(path, it)) { "Upload fallito" } } }
    override fun close() { runCatching { ftp.logout(); ftp.disconnect() } }
}

private class SftpRemote(c: RemoteConfig) : RemoteClient {
    private val session = JSch().getSession(c.user, c.host, c.port).apply {
        setPassword(c.pass)
        // Nessuna verifica dell'host key: comodo per uso domestico, meno sicuro su reti non fidate.
        setConfig("StrictHostKeyChecking", "no")
        connect(15000)
    }
    private val ch = (session.openChannel("sftp") as ChannelSftp).also { it.connect(15000) }
    @Suppress("UNCHECKED_CAST")
    override fun list(path: String): List<RemoteEntry> =
        (ch.ls(path.ifEmpty { "." }) as Vector<ChannelSftp.LsEntry>)
            .filter { it.filename != "." && it.filename != ".." }
            .map { RemoteEntry(it.filename, RemoteClient.join(path.ifEmpty { "." }, it.filename), it.attrs.isDir, it.attrs.size) }
    override fun download(path: String, dest: File) { dest.outputStream().use { ch.get(path, it) } }
    override fun upload(src: File, path: String) { src.inputStream().use { ch.put(it, path) } }
    override fun close() { runCatching { ch.disconnect(); session.disconnect() } }
}

private class SmbRemote(c: RemoteConfig) : RemoteClient {
    private val client = SMBClient()
    private val conn: Connection = client.connect(c.host, c.port)
    private val session: Session = conn.authenticate(AuthenticationContext(c.user, c.pass.toCharArray(), ""))
    private val share = session.connectShare(c.share) as DiskShare

    override fun list(path: String): List<RemoteEntry> =
        share.list(path).filter { it.fileName != "." && it.fileName != ".." }.map {
            val dir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            RemoteEntry(it.fileName, RemoteClient.join(path, it.fileName), dir, it.endOfFile)
        }

    override fun download(path: String, dest: File) {
        share.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).use { f ->
            dest.outputStream().use { o -> f.inputStream.copyTo(o) }
        }
    }

    override fun upload(src: File, path: String) {
        share.openFile(path, EnumSet.of(AccessMask.GENERIC_WRITE), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null).use { f ->
            src.inputStream().use { i -> i.copyTo(f.outputStream) }
        }
    }

    override fun close() { runCatching { share.close(); session.close(); conn.close(); client.close() } }
}
