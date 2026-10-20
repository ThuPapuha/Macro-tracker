package io.github.thupapuha.macrotracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.thupapuha.macrotracker.ui.AppNavigation
import io.github.thupapuha.macrotracker.ui.theme.MacroTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MacroTheme {
                AppNavigation()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as MacroTrackerApp).container.updater.checkIfDue()
    }
}
