package de.bibgl.konto.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import de.bibgl.konto.BuildConfig
import de.bibgl.konto.R

private const val REPO_URL = "https://github.com/daGrue11/Stadtbuecherei-GL-App"
private const val CONTACT_EMAIL = "info@klggr.de"

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Column {
                Text(
                    "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Inoffizielle App für die Stadtbücherei Bergisch Gladbach. " +
                        "Nicht von der Stadtbücherei herausgegeben.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                AboutLink("Quellcode auf GitHub", REPO_URL, REPO_URL)
                Spacer(Modifier.height(12.dp))
                AboutLink("Kontakt", CONTACT_EMAIL, "mailto:$CONTACT_EMAIL")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

@Composable
private fun AboutLink(label: String, text: String, uri: String) {
    val uriHandler = LocalUriHandler.current
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .clickable { runCatching { uriHandler.openUri(uri) } }
                .padding(vertical = 4.dp),
        )
    }
}
