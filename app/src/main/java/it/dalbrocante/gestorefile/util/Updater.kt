package it.dalbrocante.gestorefile.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import it.dalbrocante.gestorefile.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Aggiornamento in-app dalla release "Ultima build" di GitHub (la release si chiama "Build N"). */
object Updater {
    private const val API = "https://api.github.com/repos/aelleicsics92/Gestore-file/releases/tags/latest"

    data class Info(val build: Int, val url: String)

    fun currentBuild(): Int = BuildConfig.VERSION_CODE - 100

    fun check(): Info? = runCatching {
        val c = URL(API).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 10000; c.readTimeout = 10000
        val o = JSONObject(c.inputStream.bufferedReader().readText())
        val build = Regex("\\d+").find(o.optString("name"))?.value?.toInt() ?: return null
        val assets = o.getJSONArray("assets")
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name") == "GestoreFile.apk" } ?: return null
        Info(build, asset.getString("browser_download_url"))
    }.getOrNull()

    fun isNewer(i: Info) = i.build > currentBuild()

    fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File {
        val out = File(ctx.cacheDir, "update.apk")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        val total = c.contentLength.toLong()
        c.inputStream.use { i ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L
                var n = i.read(buf)
                while (n > 0) {
                    o.write(buf, 0, n); done += n
                    if (total > 0) onProgress((done * 100 / total).toInt())
                    n = i.read(buf)
                }
            }
        }
        return out
    }

    /** Apre l'installer di sistema. Se manca il permesso, apre la schermata dei permessi e ritorna false. */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(ctx, apk), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
        return true
    }
}
