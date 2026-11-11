package it.dalbrocante.gestorefile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.dalbrocante.gestorefile.data.Tools
import it.dalbrocante.gestorefile.net.FtpServerService
import it.dalbrocante.gestorefile.util.Updater
import it.dalbrocante.gestorefile.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

private enum class Panel { NONE, FTP, DUPES, GPS, UPDATE, LOOK }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(vm: FileViewModel, nav: Nav, onBack: () -> Unit, onVault: () -> Unit) {
    var panel by remember { mutableStateOf(Panel.NONE) }

    val sections: List<Pair<String, List<Tile>>> = listOf(
        "Documenti e PDF" to listOf(
            Tile(Icons.Filled.PictureAsPdf, "Strumenti PDF", Color(0xFFE5484D)) { nav.openPdfTools() },
            Tile(Icons.Filled.DocumentScanner, "Scansiona", Color(0xFF8B5CF6)) { nav.scan() },
            Tile(Icons.Filled.FindInPage, "Cerca nel testo (OCR)", Color(0xFFF59E0B)) { nav.openOcr() },
        ),
        "Organizza" to listOf(
            Tile(Icons.Filled.VerticalSplit, "Doppio pannello", Color(0xFF14B8A6)) { nav.openDual() },
            Tile(Icons.Filled.Label, "Tag", Color(0xFFEC4899)) { nav.openTags() },
            Tile(Icons.Filled.AutoFixHigh, "Regole di smistamento", Color(0xFF5B4BDB)) { nav.openRules() },
            Tile(Icons.Filled.Delete, "Cestino", Color(0xFF64748B)) { nav.openTrash() },
        ),
        "Ottimizzazione" to listOf(
            Tile(Icons.Filled.PieChart, "Analizzatore spazio", Color(0xFF5B4BDB)) { nav.openStorage() },
            Tile(Icons.Filled.BarChart, "Statistiche", Color(0xFF0EA5E9)) { nav.openStats() },
            Tile(Icons.Filled.FileCopy, "Duplicati", Color(0xFFFB7A3D)) { panel = Panel.DUPES },
            Tile(Icons.Filled.LocationOff, "Rimuovi GPS dalle foto", Color(0xFF2FA866)) { panel = Panel.GPS },
        ),
        "Rete e cloud" to listOf(
            Tile(Icons.Filled.Dns, "FTP, SFTP, SMB", Color(0xFF0EA5E9)) { nav.openRemote() },
            Tile(Icons.Filled.Cloud, "Cloud e memorie esterne", Color(0xFF3B82F6)) { nav.openCloud() },
            Tile(Icons.Filled.Watch, "Orologio (Wear OS)", Color(0xFF14B8A6)) { nav.openWatch() },
            Tile(Icons.Filled.Wifi, "Server FTP sul telefono", Color(0xFF14B8A6)) { panel = Panel.FTP },
        ),
        "Sicurezza e avanzate" to listOf(
            Tile(Icons.Filled.Lock, "Vault", Color(0xFF8B5CF6)) { onVault() },
            Tile(Icons.Filled.Terminal, "Terminale e root", Color(0xFF64748B)) { nav.openTerminal() },
        ),
        "App" to listOf(
            Tile(Icons.Filled.SystemUpdate, "Aggiornamenti", Color(0xFF2FA866)) { panel = Panel.UPDATE },
            Tile(Icons.Filled.Palette, "Aspetto", Color(0xFFEC4899)) { panel = Panel.LOOK },
        ),
    )

    Page2("Strumenti", onBack) {
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            sections.forEach { (title, tiles) ->
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(title) }
                items(tiles) { t -> ToolTile(t.icon, t.label, t.color, Modifier.fillMaxWidth(), t.onClick) }
            }
        }
    }

    if (panel != Panel.NONE) {
        ModalBottomSheet(onDismissRequest = { panel = Panel.NONE }, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (panel) {
                    Panel.FTP -> FtpPanel()
                    Panel.DUPES -> DuplicatesPanel(vm)
                    Panel.GPS -> GpsPanel(vm)
                    Panel.UPDATE -> UpdatePanel()
                    Panel.LOOK -> LookPanel()
                    Panel.NONE -> Unit
                }
            }
        }
    }
}

@Composable
private fun PanelTitle(icon: ImageVector, color: Color, title: String, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(16.dp), color = color.copy(alpha = 0.15f), modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = color) }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun FtpPanel() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(FtpServerService.running) }
    val pin = remember { "%04d".format(Random.nextInt(10000)) }
    PanelTitle(Icons.Filled.Wifi, Color(0xFF14B8A6), "Server FTP", "Accedi ai file dal PC sulla stessa Wi-Fi")
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (on) "ftp://${FtpServerService.localIp()}:${FtpServerService.PORT}\nutente: ${FtpServerService.USER}\npassword: $pin" else "Spento",
            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge,
        )
    }
    Button({ if (on) FtpServerService.stop(ctx) else FtpServerService.start(ctx, pin); on = !on }, Modifier.fillMaxWidth()) { Text(if (on) "Ferma server" else "Avvia server") }
}

@Composable
private fun DuplicatesPanel(vm: FileViewModel) {
    val scope = rememberCoroutineScope()
    var dups by remember { mutableStateOf<List<List<File>>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    PanelTitle(Icons.Filled.FileCopy, Color(0xFFFB7A3D), "Duplicati", "Cartella: ${vm.currentDir.name.ifEmpty { "Memoria" }}")
    Button({
        scope.launch { scanning = true; dups = withContext(Dispatchers.IO) { Tools.duplicates(vm.currentDir) }; scanning = false }
    }, Modifier.fillMaxWidth(), enabled = !scanning) { Text(if (scanning) "Scansione in corso…" else "Cerca duplicati") }
    if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
    dups?.let { groups ->
        if (groups.isEmpty()) Text("Nessun duplicato trovato")
        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups) { g ->
                ElevatedCard(shape = RoundedCornerShape(18.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(8.dp)) {
                        g.forEachIndexed { idx, f ->
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(f.name, maxLines = 1) },
                                supportingContent = { Text("${formatSize(f.length())}  ·  ${f.parent ?: ""}", maxLines = 1) },
                                trailingContent = {
                                    if (idx > 0) IconButton({
                                        vm.repo.moveToTrash(f)
                                        dups = dups?.map { l -> l - f }?.filter { it.size > 1 }; vm.refresh()
                                    }) { Icon(Icons.Filled.Delete, "Cestina copia", tint = MaterialTheme.colorScheme.error) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GpsPanel(vm: FileViewModel) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    PanelTitle(Icons.Filled.LocationOff, Color(0xFF2FA866), "Privacy foto", "Rimuove la posizione GPS dai JPEG")
    Text("Agisce sui JPEG selezionati, oppure su tutti quelli della cartella corrente.")
    Button({
        val targets = vm.selection.ifEmpty { vm.items.map { it.file }.toSet() }
            .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg") }
        scope.launch {
            val n = withContext(Dispatchers.IO) { targets.count { runCatching { Tools.stripGps(it) }.isSuccess } }
            status = "Posizione rimossa da $n foto"
        }
    }, Modifier.fillMaxWidth()) { Text("Rimuovi GPS") }
    status?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium) }
}

@Composable
private fun UpdatePanel() {
    val scope = rememberCoroutineScope()
    var upd by remember { mutableStateOf<Updater.Info?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    PanelTitle(Icons.Filled.SystemUpdate, Color(0xFF2FA866), "Aggiornamenti", "Build installata: ${Updater.currentBuild()}")
    Button({
        scope.launch {
            msg = null
            val i = withContext(Dispatchers.IO) { Updater.check() }
            if (i != null && Updater.isNewer(i)) upd = i else msg = if (i == null) "Controllo non riuscito" else "Sei già all'ultima versione"
        }
    }, Modifier.fillMaxWidth()) { Text("Cerca aggiornamenti") }
    msg?.let { Text(it) }
    upd?.let { UpdateDialog(it) { upd = null } }
}

@Composable
private fun LookPanel() {
    val ctx = LocalContext.current
    PanelTitle(Icons.Filled.Palette, Color(0xFFEC4899), "Aspetto")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Colori dinamici", fontWeight = FontWeight.SemiBold)
            Text("Usa i colori dello sfondo (Android 12 o successivo)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(AppPrefs.dynamic, { AppPrefs.setDynamic(ctx, it) })
    }
}
