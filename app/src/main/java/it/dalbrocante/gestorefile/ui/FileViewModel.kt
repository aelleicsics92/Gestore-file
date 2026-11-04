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
    var sort by mutableStateOf(SortMode.NAME)
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

    init { refresh() }

    /** Legge la cartella in background; una nuova richiesta annulla la precedente. */
    fun refresh() {
        listJob?.cancel()
        val dir = currentDir; val s = sort; val h = showHidden
        listJob = viewModelScope.launch {
            loading = true
            val r = withContext(Dispatchers.IO) { repo.list(dir, s, h) }
            if (dir == currentDir) items = r
            loading = false
        }
    }

    fun open(dir: File) {
        if (!dir.isDirectory) return
        currentDir = dir
        items = emptyList()
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
    fun clearSelection() { selection = emptySet() }

    fun changeSort(s: SortMode) { sort = s; refresh() }
    fun toggleHidden() { showHidden = !showHidden; refresh() }

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
