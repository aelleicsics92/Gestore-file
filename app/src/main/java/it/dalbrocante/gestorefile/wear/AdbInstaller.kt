package it.dalbrocante.gestorefile.wear

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Installa l'app sull'orologio via debug Wi-Fi (come Wear Installer 2). */
object AdbInstaller {
    private var ready = false

    /** L'identità ADB (certificato + chiave) resta salvata: l'associazione con l'orologio vale anche dopo la chiusura dell'app. */
    @Synchronized
    private fun init(ctx: Context) {
        if (ready) return
        val cf = File(ctx.filesDir, "adb_cert.bin")
        val kf = File(ctx.filesDir, "adb_key.bin")
        if (cf.exists() && kf.exists()) KadbCert.set(cf.readBytes(), kf.readBytes())
        else {
            val p = KadbCert.get()
            cf.writeBytes(p.first); kf.writeBytes(p.second)
            KadbCert.set(p.first, p.second)
        }
        ready = true
    }

    suspend fun pair(ctx: Context, host: String, port: Int, code: String) = withContext(Dispatchers.IO) {
        init(ctx)
        Kadb.pair(host, port, code)
    }

    suspend fun install(ctx: Context, host: String, port: Int, apk: File) = withContext(Dispatchers.IO) {
        init(ctx)
        val k = Kadb.create(host, port)
        try { k.install(apk) } finally { runCatching { k.close() } }
    }
}
