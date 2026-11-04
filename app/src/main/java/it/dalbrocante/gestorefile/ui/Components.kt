package it.dalbrocante.gestorefile.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue

/** Campo di testo: seleziona tutto al focus, Invio conferma, Tab/Next naviga. */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    onEnter: () -> Unit = {},
    autoFocus: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(0, value.length))) }
    val focus = remember { FocusRequester() }
    if (tfv.text != value) tfv = tfv.copy(text = value)
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = tfv,
        onValueChange = { tfv = it; onValueChange(it.text) },
        label = { Text(label) },
        singleLine = singleLine,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedBorderColor = Color.Transparent,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged { if (it.isFocused) tfv = tfv.copy(selection = TextRange(0, tfv.text.length)) },
        keyboardOptions = keyboardOptions.copy(imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onEnter() }, onGo = { onEnter() }, onSend = { onEnter() }),
    )
}

@Composable
fun InputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmText: String = "OK",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val submit = { if (text.isNotBlank()) onConfirm(text.trim()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { AppTextField(text, { text = it }, label, onEnter = submit, autoFocus = true) },
        confirmButton = { TextButton(onClick = submit) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Conferma") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
