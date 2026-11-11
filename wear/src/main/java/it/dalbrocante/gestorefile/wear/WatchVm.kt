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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

enum class Kind { HOME, DIR, FILE, ACTION, DANGER, INFO }
enum class Act { OPEN_STORAGE, OPEN_RECEIVED, GRANT, KEYS, UPDATE, UP, COPY, CUT, DELETE, CONFIRM_DELETE, INFO, SELALL, SELNONE, PASTE, MKDIR, HIDDEN, CLOSE }
enum class Menu { MAIN, CONFIRM, INFO, KEYS }

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
    private var savedCursor = 0
    private var acc = 0f

    val root: File get() = WatchStorage.root(ctx)
    private val received: File get() = WatchStorage.receiveDir(ctx)

    // ---------- righe mostrate ----------
    val rows: List<Row>
        get() {
            val m = menu
            if (m != null) return menuRows(m)
            val d = dir ?: return homeRows()
            val list = ArrayList<Row>()
            list += Row("up", "Su", d.name.ifEmpty { "/" }, Kind.ACTION, act = Act.UP)
            entries.forEach {
                list += Row(it.path, it.name, if (it.isDir) "Cartella" else formatSize(it.size), if (it.isDir) Kind.DIR else Kind.FILE, File(it.path))
            }
            if (entries.isEmpty()) list += Row("empty", "Cartella vuota", kind = Kind.INFO)
            return list
        }

    private fun homeRows(): List<Row> = buildList {
        add(Row("storage", "Memoria", if (WatchStorage.hasAll()) "Tutto l'orologio" else "Solo cartella dell'app", Kind.HOME, act = Act.OPEN_STORAGE))
        add(Row("recv", "Ricevuti dal telefono", received.name, Kind.HOME, act = Act.OPEN_RECEIVED))
        if (!WatchStorage.hasAll()) add(Row("grant", "Consenti tutti i file", "Tocca per abilitare", Kind.ACTION, act = Act.GRANT))
        add(Row("phone", phone, "", Kind.INFO))
        add(Row("keys", "Guida pulsanti", "", Kind.HOME, act = Act.KEYS))
        add(Row("upd", update, "Versione ${BuildConfig.BUILD_NUMBER}", Kind.HOME, act = Act.UPDATE))
    }

    private fun menuRows(m: Menu): List<Row> = when (m) {
        Menu.MAIN -> buildList {
            val n = menuTargets.size
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
            Row("k4", "Pulsante 2: indietro", kind = Kind.INFO),
            Row("k5", "Pulsante 2 lungo: menu azioni", kind = Kind.INFO),
            Row("k6", "Pulsante 3: file nascosti", kind = Kind.INFO),
            Row("k7", "Se un pulsante non risponde, usa il tocco", kind = Kind.INFO),
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
        while (abs(acc) >= STEP) {
            val dirn = if (acc > 0) 1 else -1
            acc -= dirn * STEP
            val n = (cursor + dirn).coerceIn(0, (rows.size - 1).coerceAtLeast(0))
            if (n != cursor) { cursor = n; moved = true }
        }
        return moved
    }

    fun activate(row: Row) {
        when (row.kind) {
            Kind.DIR -> open(row.file!!)
            Kind.FILE -> if (selection.isNotEmpty()) toggle(row) else openMenu(Menu.MAIN, listOf(row.file!!))
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
                if (long) { if (dir != null && menu == null) openMenu(Menu.MAIN, currentTargets(row)) } else if (!back()) message = "Premi indietro per uscire"
            KeyEvent.KEYCODE_STEM_3 -> if (!long && dir != null) { showHidden = !showHidden; load() }
        }
    }

    // ---------- azioni ----------
    private fun act(a: Act) {
        when (a) {
            Act.OPEN_STORAGE -> open(root)
            Act.OPEN_RECEIVED -> open(received)
            Act.GRANT -> runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { flash("Apri Impostazioni > App > Gestore file > Permessi") }
            Act.KEYS -> { savedCursor = cursor; menu = Menu.KEYS; cursor = 0 }
            Act.UPDATE -> checkUpdate()
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
            Act.SELALL -> { selection = entries.map { it.path }.toSet(); closeMenu() }
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
        Wearable.getCapabilityClient(ctx).getCapability(Proto.CAP_PHONE, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { c ->
                phone = c.nodes.firstOrNull()?.let { "Telefono: ${it.displayName}" } ?: "Telefono non collegato"
            }
            .addOnFailureListener { phone = "Telefono non collegato" }
        if (dir != null) load()
    }

    private fun checkUpdate() {
        update = "Controllo…"
        viewModelScope.launch(Dispatchers.IO) {
            val i = WatchUpdater.check()
            when {
                i == null -> update = "Aggiornamento non disponibile"
                i.build <= BuildConfig.BUILD_NUMBER -> update = "App aggiornata"
                else -> {
                    update = "Scarico build ${i.build}…"
                    runCatching { WatchUpdater.install(ctx, WatchUpdater.download(ctx, i.url)) }
                        .onSuccess { update = "Conferma l'installazione" }
                        .onFailure { update = "Errore aggiornamento" }
                }
            }
        }
    }

    companion object { private const val STEP = 48f }
}
