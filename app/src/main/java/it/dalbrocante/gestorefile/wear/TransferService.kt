package it.dalbrocante.gestorefile.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Invia i file all'orologio in primo piano, così il trasferimento continua anche con l'app chiusa. */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg(notif("Invio all'orologio…", 0, 0))
        if (job?.isActive != true) job = scope.launch { run() }
        return START_NOT_STICKY
    }

    private suspend fun run() {
        val node = WearBridge.watch(this)
        if (node == null) {
            WearBridge.update { it.copy(active = false, error = "Orologio non collegato", total = 0, done = 0) }
            stopSelf(); return
        }
        var failed: String? = null
        while (true) {
            val (items, dst) = WearBridge.next() ?: break
            for (item in items) {
                runCatching { WearBridge.sendOne(this, node.id, item, dst) }
                    .onSuccess { WearBridge.update { s -> s.copy(done = s.done + 1, label = item.rel, tick = s.tick + 1) } }
                    .onFailure { e -> failed = e.message ?: "Invio non riuscito"; WearBridge.update { s -> s.copy(done = s.done + 1) } }
                val s = WearBridge.transfer.value
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notif(item.rel, s.done, s.total))
            }
        }
        WearBridge.update { it.copy(active = false, finished = true, error = failed, label = "Invio completato", total = 0, done = 0) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
    }

    private fun notif(text: String, done: Int, total: Int): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Invio all'orologio", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Invio all'orologio")
            .setContentText(if (total > 0) "$done/$total  $text" else text)
            .setProgress(total, done, total == 0)
            .setOngoing(true).setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object { private const val ID = 4711; private const val CH = "watch_transfer" }
}
