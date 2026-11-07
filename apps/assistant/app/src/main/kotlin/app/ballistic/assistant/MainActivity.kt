// The app's one main window: prompt box, action log, settings. Holds no state of its own; the
// session and stores live in AppGraph.

package app.ballistic.assistant

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import app.ballistic.assistant.ui.BallisticTheme
import app.ballistic.assistant.ui.LocalAiScreen
import app.ballistic.assistant.ui.LogScreen
import app.ballistic.assistant.ui.PromptScreen
import app.ballistic.assistant.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onResume() {
        super.onResume()
        graph.gate.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The approval notification is the only way to reach the window from the background.
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        val appGraph = graph
        setContent {
            val localBusy by appGraph.localAi.busy.collectAsState()
            DisposableEffect(localBusy) {
                val alreadyKeptAwake = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
                if (localBusy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose {
                    if (localBusy && !alreadyKeptAwake) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            BallisticTheme {
                Surface(Modifier.fillMaxSize()) {
                    var tab by rememberSaveable { mutableIntStateOf(0) }
                    Column(Modifier.safeDrawingPadding()) {
                        Row(Modifier.fillMaxWidth()) {
                            listOf("Ask", "Local AI", "Activity", "Settings").forEachIndexed { i, name ->
                                TextButton(onClick = { tab = i }, modifier = Modifier.weight(1f).semantics { selected = tab == i }, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 12.dp)) {
                                    Text(name, textAlign = TextAlign.Center, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                        HorizontalDivider()
                        when (tab) {
                            0 -> PromptScreen(appGraph, activity = this@MainActivity, onOpenLocalAi = { tab = 1 })
                            1 -> LocalAiScreen(appGraph, activity = this@MainActivity, onOpenAsk = { tab = 0 }, onOpenSettings = { tab = 3 })
                            2 -> LogScreen(appGraph)
                            else -> SettingsScreen(appGraph, activity = this@MainActivity)
                        }
                    }
                }
            }
        }
    }
}
