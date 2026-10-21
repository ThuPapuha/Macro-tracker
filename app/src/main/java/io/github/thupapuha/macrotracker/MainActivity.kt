package io.github.thupapuha.macrotracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.thupapuha.macrotracker.data.GroupActivityNotifier
import io.github.thupapuha.macrotracker.ui.AppNavigation
import io.github.thupapuha.macrotracker.ui.theme.MacroTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Group to show after tapping a notification. */
    private val openGroupRequests = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            MacroTheme {
                AppNavigation(openGroupRequests)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        (application as MacroTrackerApp).container.updater.checkIfDue()
    }

    override fun onStop() {
        super.onStop()
        (application as MacroTrackerApp).container.backup.backupIfChanged()
    }

    private fun handleIntent(intent: Intent?) {
        val groupId = intent?.getStringExtra(GroupActivityNotifier.EXTRA_OPEN_GROUP) ?: return
        // Remove it so a configuration change does not navigate again.
        intent.removeExtra(GroupActivityNotifier.EXTRA_OPEN_GROUP)
        openGroupRequests.value = groupId
    }
}
