package it.dalbrocante.gestorefile.wear

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import it.dalbrocante.gestorefile.shared.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Riceve richieste e file dal telefono: elenco, operazioni, invio e ricezione file. */
class WatchFileService : WearableListenerService() {
    private val pool = Executors.newCachedThreadPool()

    override fun onRequest(nodeId: String, path: String, data: ByteArray): Task<ByteArray>? {
        if (path !in setOf(Proto.P_LIST, Proto.P_OP, Proto.P_PULL, Proto.P_INFO)) return null
        return Tasks.call(pool, Callable {
            runCatching { handle(nodeId, path, data) }.getOrElse { errorJson(it.message ?: "Errore") }
        })
    }

    private fun root() = WatchStorage.root(this)

    private fun inRoot(f: File): Boolean {
        val c = runCatching { f.canonicalFile }.getOrNull() ?: return false
        val r = root().canonicalFile
        return c.path == r.path || c.path.startsWith(r.path + File.separator)
    }

    private fun handle(nodeId: String, path: String, data: ByteArray): ByteArray = when (path) {
        Proto.P_INFO -> infoJson(root().absolutePath, WatchStorage.receiveDir(this).absolutePath, root().usableSpace, BuildConfig.BUILD_NUMBER)
        Proto.P_LIST -> {
            val o = JSONObject(String(data))
            val dir = o.optString("path").ifEmpty { root().absolutePath }.let { File(it) }
            if (!inRoot(dir) || !dir.isDirectory) errorJson("Cartella non accessibile")
            else listToJson(
                dir.absolutePath,
                if (dir.canonicalPath == root().canonicalPath) null else dir.parentFile?.absolutePath,
                LocalOps.list(dir, o.optBoolean("hidden")), dir.usableSpace,
            )
        }
        Proto.P_OP -> doOp(JSONObject(String(data)))
        Proto.P_PULL -> {
            val o = JSONObject(String(data))
            val arr = o.getJSONArray("paths")
            val files = (0 until arr.length()).map { File(arr.getString(it)) }.filter { inRoot(it) && it.exists() }
            val flat = LocalOps.flatten(files)
            val dst = o.getString("dst")
            pool.execute { flat.forEach { (f, rel) -> runCatching { sendFile(nodeId, f, rel, dst) } } }
            okJson(flat.size)
        }
        else -> errorJson("Richiesta sconosciuta")
    }

    private fun doOp(o: JSONObject): ByteArray {
        val arr = o.getJSONArray("paths")
        val files = (0 until arr.length()).map { File(arr.getString(it)) }
        if (files.any { !inRoot(it) || it.canonicalPath == root().canonicalPath }) return errorJson("Percorso non consentito")
        val dst = o.optString("dst").takeIf { it.isNotEmpty() }?.let { File(it) }
        if (dst != null && !inRoot(dst)) return errorJson("Destinazione non consentita")
        val name = o.optString("name")
        val err: String? = when (o.getString("op")) {
            "delete" -> if (files.all { LocalOps.delete(it) }) null else "Eliminazione non riuscita"
            "mkdir" -> LocalOps.mkdir(dst ?: return errorJson("Destinazione mancante"), name)
            "rename" -> LocalOps.rename(files.firstOrNull() ?: return errorJson("Nessun elemento"), name)
            "copy" -> files.firstNotNullOfOrNull { LocalOps.copy(it, dst ?: return errorJson("Destinazione mancante")) }
            "move" -> files.firstNotNullOfOrNull { LocalOps.move(it, dst ?: return errorJson("Destinazione mancante")) }
            else -> "Operazione sconosciuta"
        }
        return if (err == null) okJson(files.size) else errorJson(err)
    }

    /** Invia un file al telefono (usato per "scarica sul telefono"). */
    private fun sendFile(nodeId: String, f: File, rel: String, dstDir: String) {
        val cc = Wearable.getChannelClient(this)
        val ch = Tasks.await(cc.openChannel(nodeId, Proto.CH_FILE))
        val out = Tasks.await(cc.getOutputStream(ch))
        out.use { o ->
            writeHeader(o, dstDir, rel, f.length())
            f.inputStream().use { it.copyTo(o, 32 * 1024) }
        }
        cc.close(ch)
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != Proto.CH_FILE) return
        val cc = Wearable.getChannelClient(this)
        try {
            val input = Tasks.await(cc.getInputStream(channel))
            input.use { i ->
                val h = readHeader(i)
                val base = if (h.dir.isEmpty()) WatchStorage.receiveDir(this) else File(h.dir)
                val target = safeTarget(base, h.rel, root())?.let { if (h.rel == "GestoreFileWatch.apk") it else uniqueFile(it) } ?: return
                target.parentFile?.mkdirs()
                var n = 0L
                target.outputStream().use { o ->
                    val buf = ByteArray(32 * 1024)
                    while (true) { val r = i.read(buf); if (r < 0) break; o.write(buf, 0, r); n += r }
                }
                if (h.size >= 0 && n != h.size) target.delete()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { cc.close(channel) }
        }
    }

    override fun onDestroy() { pool.shutdown(); super.onDestroy() }
}
