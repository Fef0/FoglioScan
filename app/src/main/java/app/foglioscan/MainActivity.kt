package app.foglioscan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import app.foglioscan.ui.FoglioApp
import app.foglioscan.ui.theme.FoglioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Launcher shortcut "Scan" opens straight into the camera.
        val startInCamera = savedInstanceState == null && intent?.action == ACTION_SCAN
        setContent {
            FoglioTheme {
                // Exposes test tags to UI Automator (Baseline Profile generation).
                Box(Modifier.semantics { testTagsAsResourceId = true }) { FoglioApp(startInCamera) }
            }
        }
    }

    companion object {
        const val ACTION_SCAN = "app.foglioscan.action.SCAN"
    }
}
