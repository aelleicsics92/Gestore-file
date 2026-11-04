package it.dalbrocante.gestorefile.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.net.Inet4Address
import java.net.NetworkInterface

class FtpServerService : Service() {

    private var server: FtpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer(); stopSelf(); return START_NOT_STICKY
        }
        val pass = intent?.getStringExtra(EXTRA_PASS) ?: "1234"
        startForeground(1, notification("FTP attivo su ${localIp()}:$PORT"))
        startServer(pass)
        return START_STICKY
    }

    private fun startServer(pass: String) {
        if (server != null) return
        val lf = ListenerFactory().apply { port = PORT }
        val user = BaseUser().apply {
            name = USER
            password = pass
            homeDirectory = Environment.getExternalStorageDirectory().absolutePath
            authorities = listOf<Authority>(WritePermission())
        }
        val factory = FtpServerFactory().apply {
            addListener("default", lf.createListener())
            userManager.save(user)
        }
        server = factory.createServer().also { it.start() }
        running = true
    }

    private fun stopServer() {
        server?.stop(); server = null; running = false
    }

    override fun onDestroy() { stopServer(); super.onDestroy() }

    private fun notification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Server FTP", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CH).setContentTitle("Gestore file").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload).build()
    }

    companion object {
        const val PORT = 2121
        const val USER = "gestore"
        const val CH = "ftp"
        const val ACTION_STOP = "stop"
        const val EXTRA_PASS = "pass"
        @Volatile var running = false

        fun start(ctx: Context, pass: String) {
            val i = Intent(ctx, FtpServerService::class.java).putExtra(EXTRA_PASS, pass)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, FtpServerService::class.java).setAction(ACTION_STOP))
        }

        fun localIp(): String = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress
        }.getOrNull() ?: "?"
    }
}
