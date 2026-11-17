package eu.hxreborn.pixelinjector.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ComposeMaterial3Flags
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.hxreborn.pixelinjector.prefs.Prefs
import eu.hxreborn.pixelinjector.snow.SnowConfig
import eu.hxreborn.pixelinjector.ui.component.SnowfallOverlay
import eu.hxreborn.pixelinjector.ui.navigation.AppNavHost
import eu.hxreborn.pixelinjector.ui.theme.AppTheme
import eu.hxreborn.pixelinjector.ui.util.LocalDateStyle
import eu.hxreborn.pixelinjector.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false
        ComposeMaterial3Flags.isCheckboxStylingFixEnabled = true
        setContent {
            AppTheme {
                val dateStyle by viewModel.dateStyle.collectAsStateWithLifecycle()
                val prefs by viewModel.prefs.collectAsStateWithLifecycle()
                CompositionLocalProvider(LocalDateStyle provides dateStyle) {
                    Box(Modifier.fillMaxSize()) {
                        AppNavHost(viewModel)
                        if (prefs[Prefs.SNOWFALL] && prefs[Prefs.SNOWFALL_APP_OVERLAY]) {
                            SnowfallOverlay(
                                config =
                                    SnowConfig(
                                        intensity = prefs[Prefs.SNOWFALL_INTENSITY],
                                        speedPercent = prefs[Prefs.SNOWFALL_SPEED],
                                        windPercent = prefs[Prefs.SNOWFALL_WIND],
                                    ),
                            )
                        }
                    }
                }
            }
        }
        if (savedInstanceState == null) viewModel.handleTestIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.handleTestIntent(intent)
    }
}
