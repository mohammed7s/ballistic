// First run (docs/UX_SYNTHESIS.md §4.8): four short steps with a mono step counter, skippable
// once the AI choice is made. Welcome; phone lock; where AI runs (the starter download is the
// same consented LocalAiController download as in Local AI); allow apps and task notifications,
// with the Android notification prompt only after its explanation. Shown once per install.

package app.ballistic.assistant.ui

import android.app.KeyguardManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.ballistic.assistant.AppGraph
import app.ballistic.assistant.MainActivity
import app.ballistic.assistant.models.ModelBackend
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun FirstRunScreen(graph: AppGraph, activity: MainActivity, onDone: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var step by rememberSaveable { mutableIntStateOf(1) }
    var aiChosen by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val selection by graph.localAi.selection.collectAsState()
    val installed by graph.localAi.installed.collectAsState()
    val download by graph.localAi.downloadProgress.collectAsState()
    val busy by graph.localAi.busy.collectAsState()
    val starter = graph.localAi.starter
    // Changing the AI route or downloading needs requests enabled, as everywhere else.
    fun withGate(action: () -> Unit) {
        message = null
        if (graph.gate.canAcceptCommands()) action()
        else graph.gate.authenticate(activity) { e -> message = e; if (e == null) action() }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Text("SETUP $step / 4", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            when (step) {
                1 -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        BallisticMark(28.dp)
                        Text("Ballistic", style = MaterialTheme.typography.headlineSmall)
                    }
                    Text("Ballistic is an assistant that runs on your phone. It answers questions and can open apps you allow.",
                        style = MaterialTheme.typography.bodyLarge)
                    Text("Tapping, typing or scrolling inside other apps needs platform support this phone setup doesn't provide.",
                        style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                }
                2 -> {
                    Text("Phone lock", style = MaterialTheme.typography.headlineSmall)
                    Text("Requests are allowed only while your phone is unlocked. Sensitive actions also need your fingerprint in a separate approval window.",
                        style = MaterialTheme.typography.bodyLarge)
                    val secure = activity.getSystemService(KeyguardManager::class.java).isDeviceSecure
                    if (secure) KeyValue("Screen lock", "Set", mono = false)
                    else {
                        WarnBlock("No screen lock", "Set a screen lock to use Ballistic.")
                        BButton("Open security settings", { activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) })
                    }
                }
                3 -> {
                    Text("Where should AI run?", style = MaterialTheme.typography.headlineSmall)
                    Text("You can change this later in Local AI. Ballistic never switches by itself.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    RadioRow(aiChosen && selection.backend == ModelBackend.Local, "On this phone (recommended)",
                        "Download the ${size(starter.bytes)} starter (${starter.name}, ${starter.license}); other models are in Local AI. Prompts stay on the phone. Wi-Fi recommended.") {
                        withGate { graph.localAi.choose(ModelBackend.Local); aiChosen = true }
                    }
                    RadioRow(aiChosen && selection.backend == ModelBackend.Http, "Connected server",
                        "An AI server you configure in Settings › Connected AI. Prompts and screen information leave your phone.") {
                        withGate { graph.localAi.choose(ModelBackend.Http); aiChosen = true }
                    }
                    if (aiChosen && selection.backend == ModelBackend.Local) {
                        if (installed.isNotEmpty()) KeyValue("Model", "Already on this phone", mono = false)
                        else if (download == null) BButton("Download starter", { withGate { graph.localAi.download(starter.id) } },
                            enabled = !busy, emphasis = true)
                        download?.let { p ->
                            FlatProgress(p.fraction)
                            Text("${mb(p.received)} / ${mb(p.total)} MB · ${(p.fraction * 100).toInt()} %", style = LocalBallisticExtras.current.mono)
                            Text("You can continue setup or leave the app; the download keeps going.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                    }
                    message?.let { WarnBlock("Not unlocked", it) }
                }
                else -> {
                    Text("Allow apps (optional)", style = MaterialTheme.typography.headlineSmall)
                    Text("New apps start blocked. Allow the ones Ballistic may open or read notifications from in Settings › App rules; saving a rule needs your fingerprint.",
                        style = MaterialTheme.typography.bodyLarge)
                    Hairline()
                    Text("Task notifications", style = MaterialTheme.typography.titleMedium)
                    Text("While a task runs in the background, a notification shows its progress and a Stop button, and an approval request can bring you back. Android will ask you next.",
                        style = MaterialTheme.typography.bodyMedium)
                    BButton("Allow task notifications", { activity.requestNotificationPermission() }, emphasis = true)
                }
            }
        }
        Hairline()
        Row(Modifier.fillMaxWidth().padding(Space.s3), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            Text(when (step) { 1 -> "Next: phone lock"; 2 -> "Next: where AI runs"; 3 -> "Next: allow apps (optional) · task notifications"; else -> "" },
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            if (step >= 3 && aiChosen) TextLink("Skip", onDone)
            if (step > 1) BButton("Back", { step-- })
            BButton(if (step == 4) "Finish" else "Continue", { if (step == 4) onDone() else step++ }, enabled = step != 3 || aiChosen, emphasis = true)
        }
    }
}

private fun size(bytes: Long) = if (bytes >= 1_000_000_000) String.format(Locale.ROOT, "%.1f GB", bytes / 1e9) else "${bytes / 1_000_000} MB"
private fun mb(bytes: Long) = String.format(Locale.ROOT, "%,d", bytes / 1_000_000)
