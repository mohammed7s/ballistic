// The app's one main window: top bar (mark, route label, gear), the screen, the task bar while a
// task runs, and bottom navigation (Ask / Activity / Local AI). Settings is a pushed screen behind
// the gear. Holds no state of its own; the session and stores live in AppGraph
// (docs/UX_SYNTHESIS.md §4.1).

package app.ballistic.assistant

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.ballistic.assistant.ui.AppShell
import app.ballistic.assistant.ui.BallisticTheme
import app.ballistic.assistant.ui.LocalAiScreen
import app.ballistic.assistant.ui.LogScreen
import app.ballistic.assistant.ui.PromptScreen
import app.ballistic.assistant.ui.Screen
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
                    var screen by rememberSaveable { mutableStateOf(Screen.Ask) }
                    var tab by rememberSaveable { mutableStateOf(Screen.Ask) }
                    fun open(s: Screen) { if (s.isTab) tab = s; screen = s }
                    fun back() { screen = if (screen == Screen.Capabilities) Screen.Settings else tab }
                    BackHandler(enabled = !screen.isTab) { back() }
                    Box(Modifier.safeDrawingPadding()) {
                        AppShell(appGraph, this@MainActivity, screen, onOpen = ::open, onBack = ::back) {
                            when (screen) {
                                Screen.Ask -> PromptScreen(appGraph, activity = this@MainActivity, onOpen = ::open)
                                Screen.Activity -> LogScreen(appGraph)
                                Screen.LocalAi -> LocalAiScreen(appGraph, activity = this@MainActivity, onOpenAsk = { open(Screen.Ask) }, onOpenSettings = { open(Screen.Settings) })
                                Screen.Settings, Screen.Capabilities -> SettingsScreen(appGraph, activity = this@MainActivity)
                            }
                        }
                    }
                }
            }
        }
    }
}
