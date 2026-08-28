package dev.haquickaccess.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.AndroidEntryPoint
import dev.haquickaccess.tv.ui.DashboardViewModel
import dev.haquickaccess.tv.ui.HaQuickAccessApp
import kotlinx.coroutines.flow.collect

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var launchEntityId by mutableStateOf<String?>(null)
    private var launchBehavior by mutableStateOf<String?>(null)
    private var benchmarkFixtureRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launchEntityId = intent?.data?.lastPathSegment
        launchBehavior = intent?.data?.getQueryParameter("behavior")
        benchmarkFixtureRequested = intent?.getBooleanExtra(EXTRA_BENCHMARK_FIXTURE, false) == true
        setContent {
            val dashboardViewModel: DashboardViewModel = viewModel()
            val state by dashboardViewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(dashboardViewModel, benchmarkFixtureRequested) {
                if (benchmarkFixtureRequested) dashboardViewModel.enableBenchmarkFixture()
            }
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
                deepLinkEntityId = launchEntityId,
                deepLinkBehavior = launchBehavior,
                onEvent = dashboardViewModel,
            )
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchEntityId = intent.data?.lastPathSegment
        launchBehavior = intent.data?.getQueryParameter("behavior")
        benchmarkFixtureRequested = intent.getBooleanExtra(EXTRA_BENCHMARK_FIXTURE, false)
    }

    companion object {
        const val EXTRA_BENCHMARK_FIXTURE = "dev.haquickaccess.tv.extra.BENCHMARK_FIXTURE"
    }
}
