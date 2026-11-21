package com.ackstorm.walky

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(initial: Settings?, onSave: (Settings) -> Unit) {
    // Plain remember: the header value must not land in the saved instance state.
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var headerName by remember { mutableStateOf(initial?.headerName ?: "") }
    val headerValue = remember { TextFieldState(initial?.headerValue ?: "") }
    var error by remember { mutableStateOf<SettingsError?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.url)) },
            placeholder = { Text("https://") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = headerName,
            onValueChange = { headerName = it },
            label = { Text(stringResource(R.string.header_name)) },
            placeholder = { Text("X-API-Key") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedSecureTextField(
            state = headerValue,
            label = { Text(stringResource(R.string.header_value)) },
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(stringResource(it.message()), color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                when (val result = parseSettings(url, headerName, headerValue.text.toString())) {
                    is SettingsResult.Ok -> onSave(result.settings)
                    is SettingsResult.Bad -> error = result.error
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.save))
        }
        Text(
            stringResource(R.string.quick_tap_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun SettingsError.message(): Int = when (this) {
    SettingsError.URL_INVALID -> R.string.err_url_invalid
    SettingsError.URL_NOT_HTTPS -> R.string.err_url_not_https
    SettingsError.HEADER_INCOMPLETE -> R.string.err_header_incomplete
    SettingsError.HEADER_NAME_INVALID -> R.string.err_header_name_invalid
    SettingsError.HEADER_NAME_RESERVED -> R.string.err_header_name_reserved
    SettingsError.HEADER_VALUE_INVALID -> R.string.err_header_value_invalid
}
