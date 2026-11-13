package it.dalbrocante.gestorefile.wear

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

/** Installa l'app sull'orologio via debug Wi-Fi (come Wear Installer 2). */
object AdbInstaller {
    private var ready = false

    @Synchronized
    private fun init(ctx: Context) {
        if (ready) return
        KadbCert.configure(OkioFilePrivateKeyStore(File(ctx.filesDir, "adb_identity.pem").toOkioPath(), FileSystem.SYSTEM))
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
