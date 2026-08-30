package com.dissonance.r2sync.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dissonance.r2sync.data.database.AppDatabase
import com.dissonance.r2sync.data.repository.SyncRepository
import com.dissonance.r2sync.energy.EnergyManager
import com.dissonance.r2sync.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * Real background folder sync. The old in-ViewModel loop died with the
 * activity; this worker runs with an application-scoped lifetime via
 * WorkManager and drives one full pass over all auto-sync-enabled folders.
 */
class FolderSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "FolderSyncWorker"
        const val UNIQUE_FOLDER_SYNC_WORK = "r2_folder_periodic_sync"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val database = AppDatabase.getDatabase(applicationContext)
        val repository = SyncRepository(applicationContext, database)
        val energySettings = repository.loadEnergySettings()

        if (!energySettings.backgroundSyncEnabled) {
            Log.d(TAG, "Background sync disabled — skipping folder pass")
            return@withContext Result.success()
        }

        val energyManager = EnergyManager(applicationContext)
        energyManager.updateSettings(energySettings)
        if (!energyManager.energyState.value.canSync) {
            Log.d(
                TAG,
                "Energy policy blocks sync (${energyManager.energyState.value.restrictionReason}) — skipping"
            )
            return@withContext Result.success()
        }

        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val engine = SyncEngine(applicationContext, repository, energyManager, workerScope)
            val ok = engine.syncAllFoldersOnce(isManual = false)
            Log.d(TAG, "Folder sync pass finished (ok=$ok)")
            if (ok) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Folder sync pass failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            workerScope.cancel()
        }
    }
}
