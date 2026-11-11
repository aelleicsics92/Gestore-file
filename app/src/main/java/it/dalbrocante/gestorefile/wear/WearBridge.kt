package it.dalbrocante.gestorefile.wear

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import it.dalbrocante.gestorefile.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** Elemento da inviare all'orologio: [uri] sorgente, [rel] percorso relativo di destinazione. */
data class SendItem(val uri: Uri, val rel: String, val size: Long)

data class TransferState(
    val active: Boolean = false,
    val label: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
    val finished: Boolean = false,
    /** Contatore incrementato a ogni file ricevuto/inviato: serve alla UI per aggiornare l'elenco. */
    val tick: Int = 0,
)

object WearBridge {
    val transfer = MutableStateFlow(TransferState())
    private val queue = ConcurrentLinkedQueue<Pair<List<SendItem>, String>>()

    fun update(f: (TransferState) -> TransferState) { transfer.update(f) }

    // ---------- nodi ----------
    suspend fun watch(ctx: Context): Node? = withContext(Dispatchers.IO) {
        runCatching {
            val caps = Tasks.await(Wearable.getCapabilityClient(ctx).getCapability(Proto.CAP_WATCH, CapabilityClient.FILTER_REACHABLE))
            caps.nodes.firstOrNull { it.isNearby } ?: caps.nodes.firstOrNull()
        }.getOrNull()
    }

    private suspend fun request(ctx: Context, path: String, data: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val n = watch(ctx) ?: error("Orologio non collegato (app Gestore file non installata sull'orologio?)")
        Tasks.await(Wearable.getMessageClient(ctx).sendRequest(n.id, path, data))
    }

    suspend fun info(ctx: Context): WatchInfo = parseInfo(request(ctx, Proto.P_INFO, ByteArray(0)))

    suspend fun list(ctx: Context, path: String, hidden: Boolean): ListResult =
        runCatching { parseList(request(ctx, Proto.P_LIST, listReqJson(path, hidden))) }
            .getOrElse { ListResult("", null, emptyList(), 0, it.message ?: "Errore") }

    /** null = ok, altrimenti errore. */
    suspend fun op(ctx: Context, op: String, paths: List<String> = emptyList(), dst: String = "", name: String = ""): String? =
        runCatching { parseResult(request(ctx, Proto.P_OP, opJson(op, paths, dst, name))) }.getOrElse { it.message ?: "Errore" }

    /** Chiede all'orologio di inviare i file; ritorna il numero di file in arrivo o -1 in caso di errore. */
    suspend fun pull(ctx: Context, paths: List<String>, dstDir: String): Int =
        runCatching {
            val o = org.json.JSONObject(String(request(ctx, Proto.P_PULL, pullJson(paths, dstDir))))
            if (o.optBoolean("ok")) o.optInt("n") else -1
        }.getOrDefault(-1)

    // ---------- invio telefono -> orologio ----------
    fun expand(files: List<File>): List<SendItem> =
        LocalOps.flatten(files).map { (f, rel) -> SendItem(Uri.fromFile(f), rel, f.length()) }

    fun resolve(ctx: Context, uris: List<Uri>): List<SendItem> = uris.mapNotNull { u ->
        if (u.scheme == "file") u.path?.let { File(it) }?.takeIf { it.isFile }?.let { SendItem(u, it.name, it.length()) }
        else runCatching {
            ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                SendItem(u, c.getString(0) ?: "file", if (c.isNull(1)) -1 else c.getLong(1))
            }
        }.getOrNull()
    }

    /** Mette in coda l'invio e avvia il servizio in primo piano. [dstDir] vuoto = cartella "Telefono" dell'orologio. */
    fun enqueue(ctx: Context, items: List<SendItem>, dstDir: String) {
        if (items.isEmpty()) return
        queue.add(items to dstDir)
        update { it.copy(active = true, finished = false, error = null, total = it.total + items.size, label = "Invio all'orologio") }
        val i = Intent(ctx, TransferService::class.java)
        // Permesso di lettura sugli URI ricevuti dal menu Condividi: vale finché il servizio resta attivo.
        val content = items.map { it.uri }.filter { it.scheme == "content" }
        if (content.isNotEmpty()) {
            val clip = android.content.ClipData.newRawUri("", content.first())
            content.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
            i.clipData = clip
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
    }

    fun next(): Pair<List<SendItem>, String>? = queue.poll()

    suspend fun sendOne(ctx: Context, node: Node, item: SendItem, dstDir: String) = withContext(Dispatchers.IO) {
        val cc = Wearable.getChannelClient(ctx)
        val ch = Tasks.await(cc.openChannel(node.id, Proto.CH_FILE))
        val out = Tasks.await(cc.getOutputStream(ch))
        out.use { o ->
            writeHeader(o, dstDir, item.rel, item.size)
            (ctx.contentResolver.openInputStream(item.uri) ?: error("File non leggibile")).use { it.copyTo(o, 32 * 1024) }
        }
        cc.close(ch)
    }
}
