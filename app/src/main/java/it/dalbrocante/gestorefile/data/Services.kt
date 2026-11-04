package it.dalbrocante.gestorefile.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import it.dalbrocante.gestorefile.net.FtpServerService
import it.dalbrocante.gestorefile.util.IMAGE_EXT
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** OCR locale (ML Kit) con cache su disco: path -> (data modifica, testo). */
class OcrIndex(private val dir: File) {
    private val cache = File(dir, "ocr.json")
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private fun load(): JSONObject = if (cache.exists()) JSONObject(cache.readText()) else JSONObject()

    private fun recognize(bmp: Bitmap): String =
        Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0))).text

    private fun pdfText(f: File): String {
        val sb = StringBuilder()
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                for (i in 0 until minOf(r.pageCount, 5)) {
                    r.openPage(i).use { p ->
                        val w = 1400; val h = (w.toFloat() / p.width * p.height).toInt()
                        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        b.eraseColor(android.graphics.Color.WHITE)
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        sb.append(recognize(b)).append('\n')
                    }
                }
            }
        }
        return sb.toString()
    }

    /** Indicizza immagini e PDF sotto root. Ritorna il numero di file elaborati. */
    fun index(root: File, onProgress: (Int, Int) -> Unit): Int {
        val db = load()
        val files = root.walkTopDown().onEnter { !it.name.startsWith(".") }
            .filter { it.isFile && (it.extension.lowercase() in IMAGE_EXT || it.extension.equals("pdf", true)) }.toList()
        var done = 0
        files.forEachIndexed { i, f ->
            onProgress(i, files.size)
            val e = db.optJSONObject(f.absolutePath)
            if (e == null || e.optLong("m") != f.lastModified()) {
                val text = runCatching {
                    if (f.extension.equals("pdf", true)) pdfText(f) else {
                        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                        recognize(android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts))
                    }
                }.getOrDefault("")
                db.put(f.absolutePath, JSONObject().put("m", f.lastModified()).put("t", text))
                done++
            }
        }
        cache.writeText(db.toString())
        return done
    }

    fun search(root: File, query: String): List<File> {
        val db = load(); val q = query.lowercase()
        return db.keys().asSequence()
            .filter { it.startsWith(root.absolutePath) && db.getJSONObject(it).optString("t").lowercase().contains(q) }
            .map { File(it) }.filter { it.exists() }.toList()
    }
}

/** Mini server HTTP nella rete locale: serve un file con un token e una scadenza. Funziona finché l'app è in esecuzione. */
object LinkServer {
    private data class Entry(val file: File, val expires: Long)
    private val entries = ConcurrentHashMap<String, Entry>()
    private var server: ServerSocket? = null
    const val PORT = 8765

    @Synchronized
    fun share(file: File, hours: Long): String {
        ensureRunning()
        val token = UUID.randomUUID().toString().replace("-", "").take(16)
        entries[token] = Entry(file, System.currentTimeMillis() + TimeUnit.HOURS.toMillis(hours))
        return "http://${FtpServerService.localIp()}:$PORT/$token/${URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")}"
    }

    private fun ensureRunning() {
        if (server?.isClosed == false) return
        val s = ServerSocket(PORT).also { server = it }
        Thread {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                Thread { runCatching { handle(c) }; runCatching { c.close() } }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun handle(c: Socket) {
        val line = BufferedReader(InputStreamReader(c.getInputStream())).readLine() ?: return
        val token = line.split(" ").getOrNull(1)?.trim('/')?.substringBefore('/') ?: return
        val e = entries[token]
        val out = c.getOutputStream()
        if (e == null || !e.file.exists()) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()); return }
        if (System.currentTimeMillis() > e.expires) {
            entries.remove(token)
            out.write("HTTP/1.1 410 Gone\r\nContent-Length: 0\r\n\r\n".toByteArray()); return
        }
        val name = URLEncoder.encode(e.file.name, "UTF-8").replace("+", "%20")
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${e.file.length()}\r\n" +
            "Content-Disposition: attachment; filename*=UTF-8''$name\r\n\r\n").toByteArray())
        e.file.inputStream().use { it.copyTo(out) }
        out.flush()
    }
}

/** Shell semplice (senza PTY), anche come root se disponibile. */
object Shell {
    fun hasRoot(): Boolean = run("id", File("/"), true).contains("uid=0")

    fun run(cmd: String, dir: File, root: Boolean): String = runCatching {
        val pb = if (root) ProcessBuilder("su", "-c", cmd) else ProcessBuilder("sh", "-c", cmd)
        val p = pb.directory(dir).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().useLines { ls -> ls.forEach { if (out.length < 40000) out.appendLine(it) } } }
        reader.start()
        if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroy(); out.appendLine("[timeout 30s]") }
        reader.join(1000)
        out.toString()
    }.getOrElse { "Errore: ${it.message}" }
}

/** Installazione di più APK in sequenza (Android chiede comunque conferma a ogni installazione). */
object ApkInstaller {
    private val queue = ArrayDeque<File>()
    const val ACTION = "it.dalbrocante.gestorefile.INSTALL_RESULT"

    @Synchronized
    fun start(ctx: Context, apks: List<File>) {
        queue.clear(); queue.addAll(apks)
        next(ctx)
    }

    @Synchronized
    fun next(ctx: Context) {
        val f = queue.removeFirstOrNull() ?: return
        val inst = ctx.packageManager.packageInstaller
        val id = inst.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        inst.openSession(id).use { s ->
            f.inputStream().use { i -> s.openWrite("app.apk", 0, f.length()).use { o -> i.copyTo(o); s.fsync(o) } }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ACTION).setPackage(ctx.packageName), flags)
            s.commit(pi.intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val conf = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                conf?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
            }
            else -> ApkInstaller.next(ctx)
        }
    }
}
