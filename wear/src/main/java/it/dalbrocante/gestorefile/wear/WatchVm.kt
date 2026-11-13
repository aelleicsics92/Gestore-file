package it.dalbrocante.gestorefile.wear

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import it.dalbrocante.gestorefile.shared.Entry
import it.dalbrocante.gestorefile.shared.LocalOps
import it.dalbrocante.gestorefile.shared.Proto
import it.dalbrocante.gestorefile.shared.listReqJson
import it.dalbrocante.gestorefile.shared.parseList
import it.dalbrocante.gestorefile.shared.parseResult
import it.dalbrocante.gestorefile.shared.pullJson
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

enum class Kind { HOME, DIR, FILE, ACTION, DANGER, INFO }
enum class Act { OPEN_STORAGE, OPEN_RECEIVED, GRANT, KEYS, UPDATE, UP, COPY, CUT, DELETE, CONFIRM_DELETE, INFO, SELALL, SELNONE, PASTE, MKDIR, HIDDEN, CLOSE, INSTALL, INSTALL_RECEIVED, OPEN_PHONE, PHONE_GET, MENU, ROTARY }
enum class Menu { MAIN, CONFIRM, INFO, KEYS, PHONE }

data class Row(val id: String, val title: String, val sub: String = "", val kind: Kind, val file: File? = null, val act: Act? = null)

class WatchVm(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    var dir by mutableStateOf<File?>(null)
    var entries by mutableStateOf<List<Entry>>(emptyList())
    var cursor by mutableIntStateOf(0)
    var selection by mutableStateOf<Set<String>>(emptySet())
    var clip by mutableStateOf<List<File>>(emptyList())
    var clipCut by mutableStateOf(false)
    var menu by mutableStateOf<Menu?>(null)
    var menuTargets by mutableStateOf<List<File>>(emptyList())
    var message by mutableStateOf<String?>(null)
    var showHidden by mutableStateOf(false)
    var phone by mutableStateOf("Telefono: controllo…")
    var update by mutableStateOf("Cerca aggiornamenti")
    var permTick by mutableIntStateOf(0)
    var pendingApk by mutableStateOf<File?>(null)
    private val wprefs = ctx.getSharedPreferences("wear", android.content.Context.MODE_PRIVATE)
    var rotarySens by mutableIntStateOf(wprefs.getInt("rot", 1))
    var lastKey by mutableIntStateOf(0)
    var phoneMode by mutableStateOf(false)
    var pPath by mutableStateOf("")
    var pParent by mutableStateOf<String?>(null)
    var pEntries by mutableStateOf<List<Entry>>(emptyList())
    private var phoneNodeId: String? = null
    private var updateChecked = false
    var permRequester: (() -> Unit)? = null
    private var savedCursor = 0
    private var acc = 0f

    val root: File get() = WatchStorage.root(ctx)
    private val received: File get() = WatchStorage.receiveDir(ctx)

    // ---------- righe mostrate ----------
    val rows: List<Row>
        get() {
            val m = menu
            if (m != null) return menuRows(m)
            if (phoneMode) return phoneRows()
            val d = dir ?: return homeRows()
            val list = ArrayList<Row>()
            if (selection.isNotEmpty()) list += Row("sel_act", "Azioni (${selection.size})", kind = Kind.ACTION, act = Act.MENU)
            list += Row("up", "Su", d.name.ifEmpty { "/" }, Kind.ACTION, act = Act.UP)
            entries.forEach {
                list += Row(it.path, it.name, if (it.isDir) "Cartella" else formatSize(it.size), if (it.isDir) Kind.DIR else Kind.FILE, File(it.path))
            }
            if (entries.isEmpty()) list += Row("empty", "Cartella vuota", kind = Kind.INFO)
            return list
        }

    private fun homeRows(): List<Row> = buildList {
        permTick
        pendingApk?.let { add(Row("apkrecv", "Installa aggiornamento", "Ricevuto dal telefono", Kind.ACTION, act = Act.INSTALL_RECEIVED)) }
        add(Row("storage", "Memoria", if (WatchStorage.hasAll(ctx)) "Tutto l'orologio" else "Solo cartella dell'app", Kind.HOME, act = Act.OPEN_STORAGE))
        add(Row("recv", "Ricevuti dal telefono", received.name, Kind.HOME, act = Act.OPEN_RECEIVED))
        if (!WatchStorage.hasAll(ctx)) add(Row("grant", "Consenti tutti i file", "Tocca per abilitare", Kind.ACTION, act = Act.GRANT))
        if (phone.startsWith("Telefono:")) add(Row("phone", phone, "Sfoglia i suoi file", Kind.HOME, act = Act.OPEN_PHONE))
        else add(Row("phone", phone, "", Kind.INFO))
        add(Row("rot", "Ghiera: ${LABELS[rotarySens]}", "Tocca per cambiare", Kind.ACTION, act = Act.ROTARY))
        add(Row("keys", "Guida pulsanti", "", Kind.HOME, act = Act.KEYS))
        add(Row("upd", update, "Versione ${BuildConfig.BUILD_NUMBER}", Kind.HOME, act = Act.UPDATE))
    }

    private fun phoneRows(): List<Row> {
        val list = ArrayList<Row>()
        if (selection.isNotEmpty()) list += Row("sel_act", "Azioni (${selection.size})", kind = Kind.ACTION, act = Act.MENU)
        list += Row("up", "Su", pPath.substringAfterLast('/').ifEmpty { "Telefono" }, Kind.ACTION, act = Act.UP)
        pEntries.forEach {
            list += Row(it.path, it.name, if (it.isDir) "Cartella" else formatSize(it.size), if (it.isDir) Kind.DIR else Kind.FILE, File(it.path))
        }
        if (pEntries.isEmpty()) list += Row("empty", "Cartella vuota", kind = Kind.INFO)
        return list
    }

    private fun menuRows(m: Menu): List<Row> = when (m) {
        Menu.PHONE -> buildList {
            val n = menuTargets.size
            if (n > 0) add(Row("p_get", "Scarica sull'orologio ($n)", "Cartella: Ricevuti", Kind.ACTION, act = Act.PHONE_GET))
            add(Row("m_all", "Seleziona tutto", kind = Kind.ACTION, act = Act.SELALL))
            add(Row("m_close", "Chiudi", kind = Kind.ACTION, act = Act.CLOSE))
        }
        Menu.MAIN -> buildList {
            val n = menuTargets.size
            if (n == 1 && menuTargets[0].isFile && menuTargets[0].extension.equals("apk", true)) add(Row("m_inst", "Installa", "App", Kind.ACTION, act = Act.INSTALL))
            add(Row("m_copy", "Copia ($n)", kind = Kind.ACTION, act = Act.COPY))
            add(Row("m_cut", "Sposta ($n)", kind = Kind.ACTION, act = Act.CUT))
            if (clip.isNotEmpty()) add(Row("m_paste", "Incolla qui (${clip.size})", if (clipCut) "Sposta" else "Copia", Kind.ACTION, act = Act.PASTE))
            add(Row("m_del", "Elimina ($n)", kind = Kind.DANGER, act = Act.DELETE))
            if (n == 1) add(Row("m_info", "Dettagli", kind = Kind.ACTION, act = Act.INFO))
            add(Row("m_all", "Seleziona tutto", kind = Kind.ACTION, act = Act.SELALL))
            if (selection.isNotEmpty()) add(Row("m_none", "Annulla selezione", kind = Kind.ACTION, act = Act.SELNONE))
            add(Row("m_mk", "Nuova cartella", kind = Kind.ACTION, act = Act.MKDIR))
            add(Row("m_hid", if (showHidden) "Nascondi file nascosti" else "Mostra file nascosti", kind = Kind.ACTION, act = Act.HIDDEN))
            add(Row("m_close", "Chiudi", kind = Kind.ACTION, act = Act.CLOSE))
        }
        Menu.CONFIRM -> listOf(
            Row("c_q", "Eliminare ${menuTargets.size} element${if (menuTargets.size == 1) "o" else "i"}?", kind = Kind.INFO),
            Row("c_yes", "Sì, elimina", kind = Kind.DANGER, act = Act.CONFIRM_DELETE),
            Row("c_no", "Annulla", kind = Kind.ACTION, act = Act.CLOSE),
        )
        Menu.INFO -> menuTargets.firstOrNull()?.let { f ->
            listOf(
                Row("i1", f.name, kind = Kind.INFO),
                Row("i2", if (f.isDirectory) "Cartella" else formatSize(f.length()), kind = Kind.INFO),
                Row("i3", formatDate(f.lastModified()), kind = Kind.INFO),
                Row("i4", f.parent ?: "", kind = Kind.INFO),
                Row("i5", "Chiudi", kind = Kind.ACTION, act = Act.CLOSE),
            )
        } ?: emptyList()
        Menu.KEYS -> listOf(
            Row("k1", "Ghiera: sposta il cursore", kind = Kind.INFO),
            Row("k2", "Pulsante 1: apri / azioni", kind = Kind.INFO),
            Row("k3", "Pulsante 1 lungo: seleziona", kind = Kind.INFO),
            Row("k4", "Scorri dall'alto verso il basso: indietro", kind = Kind.INFO),
            Row("k5", "Con selezione: riga Azioni in alto", kind = Kind.INFO),
            Row("k6", "Pulsante 3: file nascosti", kind = Kind.INFO),
            Row("k7", "Se un pulsante non risponde, usa il tocco", kind = Kind.INFO),
            Row("k9", "Ultimo tasto: ${android.view.KeyEvent.keyCodeToString(lastKey)}", kind = Kind.INFO),
            Row("k8", "Chiudi", kind = Kind.ACTION, act = Act.CLOSE),
        )
    }

    // ---------- navigazione ----------
    fun open(d: File, restoreName: String? = null) {
        dir = d; selection = emptySet(); cursor = 0
        load(restoreName)
    }

    private fun load(focusPath: String? = null) {
        val d = dir ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val l = LocalOps.list(d, showHidden)
            entries = l
            if (focusPath != null) {
                val i = l.indexOfFirst { it.path == focusPath }
                if (i >= 0) cursor = i + 1
            }
        }
    }

    /** true se la pressione di "indietro" è stata gestita. */
    fun back(): Boolean {
        if (menu != null) { closeMenu(); return true }
        if (selection.isNotEmpty()) { selection = emptySet(); return true }
        if (phoneMode) { val p = pParent; if (p == null) { phoneMode = false; cursor = 0 } else openPhone(p); return true }
        val d = dir ?: return false
        if (d.canonicalPath == root.canonicalPath || d.parentFile == null) { dir = null; cursor = 0; return true }
        open(d.parentFile!!, d.absolutePath)
        return true
    }

    private fun openMenu(m: Menu, targets: List<File> = menuTargets) {
        if (menu == null) savedCursor = cursor
        menuTargets = targets; menu = m; cursor = 0
    }

    private fun closeMenu() { menu = null; cursor = savedCursor.coerceAtMost((rows.size - 1).coerceAtLeast(0)) }

    private fun currentTargets(row: Row?): List<File> =
        if (selection.isNotEmpty()) selection.map { File(it) } else listOfNotNull(row?.file)

    // ---------- input ----------
    fun rotate(px: Float): Boolean {
        acc += px
        var moved = false
        val step = STEPS[rotarySens]
        while (abs(acc) >= step) {
            val dirn = if (acc > 0) 1 else -1
            acc -= dirn * step
            val n = (cursor + dirn).coerceIn(0, (rows.size - 1).coerceAtLeast(0))
            if (n != cursor) { cursor = n; moved = true }
        }
        return moved
    }

    fun activate(row: Row) {
        when (row.kind) {
            Kind.DIR -> if (phoneMode) openPhone(row.file!!.path) else open(row.file!!)
            Kind.FILE -> if (selection.isNotEmpty() || phoneMode) toggle(row) else openMenu(Menu.MAIN, listOf(row.file!!))
            Kind.INFO -> Unit
            else -> row.act?.let { act(it) }
        }
    }

    fun longPress(row: Row) {
        if (menu == null && (row.kind == Kind.DIR || row.kind == Kind.FILE)) toggle(row)
    }

    private fun toggle(row: Row) {
        val p = row.file?.absolutePath ?: return
        selection = if (p in selection) selection - p else selection + p
    }

    fun onKey(code: Int, long: Boolean) {
        val row = rows.getOrNull(cursor)
        when (code) {
            KeyEvent.KEYCODE_STEM_1, KeyEvent.KEYCODE_STEM_PRIMARY ->
                if (long) row?.let { longPress(it) } else row?.let { activate(it) }
            KeyEvent.KEYCODE_STEM_2 ->
                if (long) { if ((dir != null || phoneMode) && menu == null) openMenu(if (phoneMode) Menu.PHONE else Menu.MAIN, currentTargets(row)) } else if (!back()) message = "Premi indietro per uscire"
            KeyEvent.KEYCODE_STEM_3 -> if (!long && dir != null) { showHidden = !showHidden; load() }
        }
    }

    // ---------- azioni ----------
    private fun act(a: Act) {
        when (a) {
            Act.OPEN_STORAGE -> open(root)
            Act.OPEN_RECEIVED -> open(received)
            Act.GRANT -> permRequester?.invoke()
            Act.KEYS -> { savedCursor = cursor; menu = Menu.KEYS; cursor = 0 }
            Act.UPDATE -> checkUpdate()
            Act.INSTALL -> { val f = menuTargets.firstOrNull(); closeMenu(); if (f != null) installApk(f) }
            Act.INSTALL_RECEIVED -> pendingApk?.let { installApk(it) }
            Act.UP -> back()
            Act.COPY -> { clip = menuTargets; clipCut = false; selection = emptySet(); closeMenu(); flash("Copiati ${clip.size}") }
            Act.CUT -> { clip = menuTargets; clipCut = true; selection = emptySet(); closeMenu(); flash("Pronti da spostare ${clip.size}") }
            Act.DELETE -> menu = Menu.CONFIRM.also { cursor = 1 }
            Act.CONFIRM_DELETE -> {
                val t = menuTargets
                io { val ok = t.count { LocalOps.delete(it) }; flash("Eliminati $ok"); selection = emptySet() }
                closeMenu()
            }
            Act.INFO -> { menu = Menu.INFO; cursor = 0 }
            Act.SELALL -> { selection = (if (phoneMode) pEntries else entries).map { it.path }.toSet(); closeMenu() }
            Act.ROTARY -> { rotarySens = (rotarySens + 1) % STEPS.size; wprefs.edit().putInt("rot", rotarySens).apply(); flash("Ghiera: ${LABELS[rotarySens]}") }
            Act.OPEN_PHONE -> openPhone("")
            Act.MENU -> openMenu(if (phoneMode) Menu.PHONE else Menu.MAIN, selection.map { File(it) })
            Act.PHONE_GET -> { val t = menuTargets.map { it.path }; val dst = received.absolutePath; closeMenu(); selection = emptySet(); phoneGet(t, dst) }
            Act.SELNONE -> { selection = emptySet(); closeMenu() }
            Act.PASTE -> {
                val d = dir; val c = clip; val cut = clipCut
                closeMenu()
                if (d != null) io {
                    val err = c.firstNotNullOfOrNull { if (cut) LocalOps.move(it, d) else LocalOps.copy(it, d) }
                    if (cut) clip = emptyList()
                    flash(err ?: "Fatto")
                }
            }
            Act.MKDIR -> { val d = dir; closeMenu(); if (d != null) io { flash(LocalOps.mkdir(d, "Nuova cartella") ?: "Cartella creata") } }
            Act.HIDDEN -> { showHidden = !showHidden; closeMenu(); load() }
            Act.CLOSE -> closeMenu()
        }
    }

    private fun io(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { runCatching(block); load() }
    }

    fun flash(msg: String) {
        message = msg
        viewModelScope.launch { delay(2500); if (message == msg) message = null }
    }

    // ---------- stato ----------
    fun refreshStatus() {
        permTick++
        viewModelScope.launch(Dispatchers.IO) {
            pendingApk = runCatching { findPending() }.getOrNull()
            if (!updateChecked) {
                updateChecked = true
                WatchUpdater.check()?.let { if (it.build > BuildConfig.BUILD_NUMBER) update = "Aggiorna alla build ${it.build}" }
            }
        }
        Wearable.getCapabilityClient(ctx).getCapability(Proto.CAP_PHONE, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { c ->
                val n = c.nodes.firstOrNull(); phoneNodeId = n?.id
                phone = n?.let { "Telefono: ${it.displayName}" } ?: "Telefono non collegato"
            }
            .addOnFailureListener { phone = "Telefono non collegato" }
        if (dir != null) load()
    }

    private fun openPhone(path: String) {
        phoneMode = true; selection = emptySet(); cursor = 0
        viewModelScope.launch(Dispatchers.IO) {
            val id = phoneNodeId
            if (id == null) { flash("Telefono non collegato"); phoneMode = false; return@launch }
            runCatching {
                val r = parseList(Tasks.await(Wearable.getMessageClient(ctx).sendRequest(id, Proto.P_PHONE_LIST, listReqJson(path, showHidden))))
                val err = r.error
                if (err != null) { flash(err); if (pEntries.isEmpty()) phoneMode = false }
                else { pPath = r.path; pParent = r.parent; pEntries = r.entries }
            }.onFailure { flash("Telefono non raggiungibile"); phoneMode = false }
        }
    }

    private fun phoneGet(paths: List<String>, dst: String) {
        flash("Richiesta al telefono…")
        viewModelScope.launch(Dispatchers.IO) {
            val id = phoneNodeId ?: return@launch flash("Telefono non collegato")
            runCatching {
                val b = Tasks.await(Wearable.getMessageClient(ctx).sendRequest(id, Proto.P_PHONE_SEND, pullJson(paths, dst)))
                flash(parseResult(b) ?: "In arrivo in Ricevuti dal telefono")
            }.onFailure { flash("Telefono non raggiungibile") }
        }
    }

    private fun installApk(f: File) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            val opened = runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (opened) { flash("Attiva \"Consenti da questa origine\", poi riprova"); return }
        }
        flash("Avvio installazione…")
        viewModelScope.launch(Dispatchers.IO) { runCatching { WatchUpdater.install(ctx, f) }.onFailure { flash("Errore: ${it.message}") } }
    }

    private fun findPending(): File? {
        val f = File(received, "GestoreFileWatch.apk").takeIf { it.exists() } ?: return null
        val v = ctx.packageManager.getPackageArchiveInfo(f.path, 0)?.longVersionCode ?: return null
        return f.takeIf { v > BuildConfig.VERSION_CODE }
    }

    private fun checkUpdate() {
        update = "Controllo…"
        viewModelScope.launch(Dispatchers.IO) {
            val i = WatchUpdater.check()
            when {
                i == null -> update = "Aggiornamento non disponibile"
                i.build <= BuildConfig.BUILD_NUMBER -> update = "App aggiornata"
                else -> {
                    update = "Scarico build ${i.build}… 0%"
                    runCatching { WatchUpdater.install(ctx, WatchUpdater.download(ctx, i.url) { p -> update = "Scarico build ${i.build}… $p%" }) }
                        .onSuccess { update = "Conferma l'installazione" }
                        .onFailure { update = "Errore aggiornamento" }
                }
            }
        }
    }

    companion object {
        private val STEPS = floatArrayOf(40f, 100f, 180f)
        private val LABELS = arrayOf("Alta", "Media", "Bassa")
    }
}
