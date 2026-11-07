package app.ballistic.assistant.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import app.ballistic.assistant.AppGraph

/** App authentication is distinct from Android's capability grants and action approval. */
@Composable
internal fun SessionAccess(graph: AppGraph, activity: Activity) {
    val unlocked by graph.gate.unlocked.collectAsState()
    var failure by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (unlocked) "Ballistic session authenticated" else "Authenticate to set up AI or change settings",
            style = MaterialTheme.typography.titleMedium)
        Text("Your fingerprint verifies who may give Ballistic requests. The session locks when you leave the app or turn off the screen. It does not grant control of other apps.",
            style = MaterialTheme.typography.bodySmall)
        if (!unlocked) OutlinedButton(onClick = {
            graph.gate.authenticate(activity) { error ->
                failure = error
                if (error == null) graph.session.onUnlocked() else graph.session.onUnlockCancelled()
            }
        }) { Text("Authenticate with fingerprint") }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
