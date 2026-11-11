package it.dalbrocante.gestorefile.wear

import android.os.Environment
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import it.dalbrocante.gestorefile.shared.Proto
import it.dalbrocante.gestorefile.shared.readHeader
import it.dalbrocante.gestorefile.shared.safeTarget
import it.dalbrocante.gestorefile.shared.uniqueFile
import java.io.File

/** Riceve sul telefono i file inviati dall'orologio ("Scarica sul telefono"). */
class PhoneFileService : WearableListenerService() {
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != Proto.CH_FILE) return
        val cc = Wearable.getChannelClient(this)
        var err: String? = null
        try {
            val input = Tasks.await(cc.getInputStream(channel))
            input.use { i ->
                val h = readHeader(i)
                val root = Environment.getExternalStorageDirectory()
                val base = if (h.dir.isEmpty()) File(root, "Download/Orologio") else File(h.dir)
                val target = safeTarget(base, h.rel, root)?.let { uniqueFile(it) } ?: error("Percorso non valido")
                target.parentFile?.mkdirs()
                var n = 0L
                target.outputStream().use { o ->
                    val buf = ByteArray(32 * 1024)
                    while (true) { val r = i.read(buf); if (r < 0) break; o.write(buf, 0, r); n += r }
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
}
