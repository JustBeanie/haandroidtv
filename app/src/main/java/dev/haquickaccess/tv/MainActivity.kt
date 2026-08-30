package dev.haquickaccess.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.haquickaccess.tv.ui.DashboardViewModel
import dev.haquickaccess.tv.ui.HaQuickAccessApp
import dev.haquickaccess.tv.data.LaunchIntentValidator
import kotlinx.coroutines.flow.collect

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val dashboardViewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        dispatchLaunchIntent(intent)
        setContent {
            val state by dashboardViewModel.uiState.collectAsStateWithLifecycle()
            val channelRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
            LaunchedEffect(dashboardViewModel) {
                dashboardViewModel.homeChannelRequests.collect { channelId ->
                    channelRequest.launch(
                        android.content.Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE)
                            .putExtra(TvContractCompat.EXTRA_CHANNEL_ID, channelId),
                    )
                }
            }
            HaQuickAccessApp(
                state = state,
                onEvent = dashboardViewModel,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchLaunchIntent(intent)
    }

    private fun dispatchLaunchIntent(intent: Intent?) {
        val request = LaunchIntentValidator.parse(intent?.data?.toString())
        val benchmarkFixtureRequested = intent?.getBooleanExtra(EXTRA_BENCHMARK_FIXTURE, false) == true
        val refreshHomeChannelRequested = intent?.getBooleanExtra(EXTRA_REFRESH_HOME_CHANNEL, false) == true
        if (request != null) {
            dashboardViewModel.handleLaunchRequest(request.entityId, request.behavior)
        } else if (benchmarkFixtureRequested) {
            dashboardViewModel.handleLaunchRequest(null, null, benchmarkFixtureRequested = true)
        }
        if (refreshHomeChannelRequested) dashboardViewModel.refreshHomeChannelWhenReady()
    }

    companion object {
        const val EXTRA_BENCHMARK_FIXTURE = "dev.haquickaccess.tv.extra.BENCHMARK_FIXTURE"
        const val EXTRA_REFRESH_HOME_CHANNEL = "dev.haquickaccess.tv.extra.REFRESH_HOME_CHANNEL"
    }
}
