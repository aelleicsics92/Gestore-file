package it.dalbrocante.gestorefile.wear

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Aggiornamento dell'app dell'orologio dalla release GitHub (asset GestoreFileWatch.apk). */
object WatchUpdater {
    private const val API = "https://api.github.com/repos/aelleicsics92/Gestore-file/releases/tags/latest"

    data class Info(val build: Int, val url: String)

    fun check(): Info? = runCatching {
        val c = URL(API).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 15000; c.readTimeout = 15000
        val o = JSONObject(c.inputStream.bufferedReader().readText())
        val build = Regex("\\d+").find(o.optString("name"))?.value?.toInt() ?: return null
        val assets = o.getJSONArray("assets")
        val a = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name") == "GestoreFileWatch.apk" } ?: return null
        Info(build, a.getString("browser_download_url"))
    }.getOrNull()

    fun download(ctx: Context, url: String, onProgress: (Int) -> Unit = {}): File {
        val out = File(ctx.cacheDir, "update.apk")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 60000
        val total = c.contentLengthLong
        c.inputStream.use { i ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; onProgress(p) } }
                }
            }
        }
        return out
    }

    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        pi.openSession(id).use { s ->
            apk.inputStream().use { i -> s.openWrite("app", 0, apk.length()).use { o -> i.copyTo(o, 64 * 1024); s.fsync(o) } }
            val intent = Intent(ctx, InstallResultReceiver::class.java).setPackage(ctx.packageName)
            val pending = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(pending.intentSender)
        }
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> android.widget.Toast.makeText(ctx, "Installato", android.widget.Toast.LENGTH_LONG).show()
            else -> android.widget.Toast.makeText(ctx, "Installazione non riuscita (${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: st})", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
