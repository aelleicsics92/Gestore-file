package it.dalbrocante.gestorefile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.dalbrocante.gestorefile.data.SearchFilter
import java.util.concurrent.TimeUnit

@Composable
fun SearchDialog(onDismiss: () -> Unit, onSearch: (SearchFilter) -> Unit) {
    var q by remember { mutableStateOf("") }
    var ext by remember { mutableStateOf("") }
    var minMb by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("") }
    val go = {
        onSearch(
            SearchFilter(
                query = q.trim(),
                extensions = ext.split(",", " ").map { it.trim().lowercase().removePrefix(".") }.filter { it.isNotEmpty() }.toSet(),
                minSize = (minMb.toDoubleOrNull() ?: 0.0).times(1024 * 1024).toLong(),
                modifiedAfter = days.toLongOrNull()?.let { System.currentTimeMillis() - TimeUnit.DAYS.toMillis(it) } ?: 0L,
            ),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cerca") },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
                AppTextField(q, { q = it }, "Nome contiene", imeAction = ImeAction.Next, autoFocus = true)
                AppTextField(ext, { ext = it }, "Estensioni (pdf, jpg)", Modifier.padding(top = 6.dp), imeAction = ImeAction.Next)
                AppTextField(
                    minMb, { minMb = it }, "Dimensione minima (MB)", Modifier.padding(top = 6.dp),
                    imeAction = ImeAction.Next, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                AppTextField(
                    days, { days = it }, "Modificati negli ultimi (giorni)", Modifier.padding(top = 6.dp),
                    onEnter = go, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = { TextButton(onClick = go) { Text("Cerca") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
