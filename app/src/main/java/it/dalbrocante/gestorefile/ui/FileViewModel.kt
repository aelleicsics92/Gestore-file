package it.dalbrocante.gestorefile.ui

import android.app.Application
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.dalbrocante.gestorefile.data.FileItem
import it.dalbrocante.gestorefile.data.FileRepository
import it.dalbrocante.gestorefile.data.SearchFilter
import it.dalbrocante.gestorefile.data.Store
import it.dalbrocante.gestorefile.data.SortMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ClipMode { COPY, CUT }

class FileViewModel(app: Application) : AndroidViewModel(app) {

    val repo = FileRepository(File(app.filesDir, ".trash"))
    val store = Store(app.filesDir)
    val root: File = Environment.getExternalStorageDirectory()

    var currentDir by mutableStateOf(root)
        private set
    var items by mutableStateOf<List<FileItem>>(emptyList())
        private set
    var selection by mutableStateOf<Set<File>>(emptySet())
        private set
    var sort by mutableStateOf(SortMode.AUTO)
        private set
    var showHidden by mutableStateOf(false)
        private set
    var clipboard by mutableStateOf<List<File>>(emptyList())
        private set
    var clipMode by mutableStateOf(ClipMode.COPY)
        private set
    var message by mutableStateOf<String?>(null)
    var searchResults by mutableStateOf<List<FileItem>?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    private var listJob: Job? = null
    val listState = androidx.compose.foundation.lazy.LazyListState()
    val gridState = androidx.compose.foundation.lazy.grid.LazyGridState()
    private val positions = HashMap<String, Pair<Int, Int>>()
    /** Posizione di scroll da ripristinare quando l'elenco della cartella corrente è pronto. */
    var restore by mutableStateOf<Pair<Int, Int>?>(null)
    /** File da mettere a fuoco (scroll + evidenziazione) dopo l'apertura del suo percorso. */
    var focusFile by mutableStateOf<File?>(null)

    fun openAt(file: File) {
        val dir = file.parentFile ?: return
        focusFile = file
        open(dir)
    }

    private fun savePosition() {
        positions[currentDir.path] =
            if (gridMode) gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
            else listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
    }
    private val cache = LinkedHashMap<String, List<FileItem>>()
    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    var gridMode by mutableStateOf(prefs.getBoolean("grid", false))
        private set

    fun toggleGrid() { gridMode = !gridMode; prefs.edit().putBoolean("grid", gridMode).apply() }

    init { refresh() }

    /** Legge la cartella in background; una nuova richiesta annulla la precedente. */
    fun refresh() {
        listJob?.cancel()
        val dir = currentDir; val s = sort; val h = showHidden
        listJob = viewModelScope.launch {
            loading = true
            val r = withContext(Dispatchers.IO) { repo.list(dir, s, h) }
            cache.remove(dir.path); cache[dir.path] = r
            while (cache.size > 12) cache.remove(cache.keys.first())
            if (dir == currentDir) items = r
            loading = false
        }
    }

    fun open(dir: File) {
        if (!dir.isDirectory) return
        if (dir != currentDir) savePosition()
        restore = if (dir == currentDir) restore else (positions[dir.path] ?: (0 to 0))
        currentDir = dir
        items = cache[dir.path] ?: emptyList()
        selection = emptySet()
        searchResults = null
        refresh()
    }

    fun up(): Boolean {
        if (searchResults != null) { searchResults = null; return true }
        if (selection.isNotEmpty()) { selection = emptySet(); return true }
        val parent = currentDir.parentFile
        if (currentDir == root || parent == null) return false
        open(parent)
        return true
    }

    fun toggleSelect(f: File) {
        selection = if (f in selection) selection - f else selection + f
    }

    fun selectAll() { selection = items.map { it.file }.toSet() }
    fun setClip(files: List<File>, mode: ClipMode) { clipboard = files; clipMode = mode }
    fun replaceSelection(s: Set<File>) { selection = s }
    fun clearSelection() { selection = emptySet() }

    fun changeSort(s: SortMode) { sort = s; cache.clear(); refresh() }
    fun toggleHidden() { showHidden = !showHidden; cache.clear(); refresh() }

    fun copySelection() { clipboard = selection.toList(); clipMode = ClipMode.COPY; selection = emptySet() }
    fun cutSelection() { clipboard = selection.toList(); clipMode = ClipMode.CUT; selection = emptySet() }

    fun paste() = runIo {
        val errs = clipboard.mapNotNull { f ->
            val r = if (clipMode == ClipMode.COPY) repo.copy(f, currentDir) else repo.move(f, currentDir)
            r.exceptionOrNull()?.message
        }
        if (clipMode == ClipMode.CUT) clipboard = emptyList()
        errs.firstOrNull()
    }

    fun createFolder(name: String) = runIo { repo.createFolder(currentDir, name).exceptionOrNull()?.message }
    fun createFile(name: String) = runIo { repo.createFile(currentDir, name).exceptionOrNull()?.message }
    fun rename(f: File, name: String) = runIo { repo.rename(f, name).exceptionOrNull()?.message }

    fun trashSelection() = runIo {
        selection.forEach { repo.moveToTrash(it) }
        selection = emptySet()
        null
    }

    fun deleteSelectionPermanently() = runIo {
        selection.forEach { repo.deletePermanently(it) }
        selection = emptySet()
        null
    }

    fun search(filter: SearchFilter) {
        viewModelScope.launch {
            busy = true
            searchResults = withContext(Dispatchers.IO) { repo.search(currentDir, filter) }
            busy = false
        }
    }

    fun runIo(block: () -> String?) {
        viewModelScope.launch {
            busy = true
            val err = withContext(Dispatchers.IO) { block() }
            busy = false
            if (err != null) message = err
            refresh()
        }
    }
}
