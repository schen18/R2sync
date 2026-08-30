package com.dissonance.r2sync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dissonance.r2sync.ui.MainScreen
import com.dissonance.r2sync.ui.SyncViewModel
import com.dissonance.r2sync.ui.theme.MyApplicationTheme
import com.dissonance.r2sync.work.R2SyncWorkScheduler

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The periodic jobs are what give both sync paths their own freshness:
        // without them, remote changes only reach companion apps' vaults when
        // they themselves write something, and folder sync only ran while the
        // UI was alive.
        R2SyncWorkScheduler.schedulePeriodicSync(this)
        run {
            val database = com.dissonance.r2sync.data.database.AppDatabase.getDatabase(this)
            val settings = com.dissonance.r2sync.data.repository.SyncRepository(this, database).loadEnergySettings()
            R2SyncWorkScheduler.scheduleFolderSync(
                this,
                settings.globalIntervalMinutes.toLong(),
                settings.syncOnlyOnWifi,
                settings.syncOnlyWhileCharging,
                settings.pauseOnLowBattery
            )
        }
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: SyncViewModel = viewModel()
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }
}
