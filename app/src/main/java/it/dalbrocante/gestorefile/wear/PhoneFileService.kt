package it.dalbrocante.gestorefile.wear

import android.os.Environment
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import it.dalbrocante.gestorefile.shared.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Servizio sul telefono: riceve file dall'orologio e risponde alle sue richieste (elenco file, invio file). */
class PhoneFileService : WearableListenerService() {
    private val pool = Executors.newCachedThreadPool()
    private val root get() = Environment.getExternalStorageDirectory()

    private fun inRoot(f: File): Boolean {
        val c = runCatching { f.canonicalFile }.getOrNull() ?: return false
        val r = root.canonicalFile
        return c.path == r.path || c.path.startsWith(r.path + File.separator)
    }

    override fun onRequest(nodeId: String, path: String, data: ByteArray): Task<ByteArray>? {
        if (path != Proto.P_PHONE_LIST && path != Proto.P_PHONE_SEND) return null
        return Tasks.call(pool, Callable {
            runCatching { handle(nodeId, path, data) }.getOrElse { errorJson(it.message ?: "Errore") }
        })
    }

    private fun handle(nodeId: String, path: String, data: ByteArray): ByteArray {
        val o = JSONObject(String(data))
        if (path == Proto.P_PHONE_LIST) {
            val dir = File(o.optString("path").ifEmpty { root.absolutePath })
            if (!inRoot(dir) || !dir.isDirectory) return errorJson("Cartella non accessibile")
            val parent = if (dir.canonicalPath == root.canonicalPath) null else dir.parentFile?.absolutePath
            return listToJson(dir.absolutePath, parent, LocalOps.list(dir, o.optBoolean("hidden")), dir.usableSpace)
        }
        // Invio all'orologio dei file scelti dall'orologio (sola lettura: il telefono non viene modificato)
        val arr = o.getJSONArray("paths")
        val files = (0 until arr.length()).map { File(arr.getString(it)) }.filter { inRoot(it) && it.exists() }
        val items = WearBridge.expand(files)
        val dst = o.getString("dst")
        pool.execute {
            items.forEach { item ->
                runCatching { runBlocking { WearBridge.sendOne(this@PhoneFileService, nodeId, item, dst) } }
            }
        }
        return okJson(items.size)
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != Proto.CH_FILE) return
        val cc = Wearable.getChannelClient(this)
        var err: String? = null
        try {
            val input = Tasks.await(cc.getInputStream(channel))
            input.use { i ->
                val h = readHeader(i)
                val base = if (h.dir.isEmpty()) File(root, "Download/Orologio") else File(h.dir)
                val target = safeTarget(base, h.rel, root)?.let { uniqueFile(it) } ?: error("Percorso non valido")
                target.parentFile?.mkdirs()
                var n = 0L
                target.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) { val r = i.read(buf); if (r < 0) break; out.write(buf, 0, r); n += r }
                }
                if (h.size >= 0 && n != h.size) { target.delete(); error("Trasferimento interrotto") }
            }
        } catch (e: Exception) {
            err = e.message ?: "Ricezione non riuscita"
        } finally {
            runCatching { cc.close(channel) }
            WearBridge.update { s -> s.copy(done = s.done + 1, tick = s.tick + 1, error = err ?: s.error, finished = s.done + 1 >= s.total) }
        }
    }

    override fun onDestroy() { pool.shutdown(); super.onDestroy() }
}
