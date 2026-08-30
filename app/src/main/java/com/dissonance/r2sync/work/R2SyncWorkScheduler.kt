package com.dissonance.r2sync.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dissonance.r2sync.data.database.AppDatabase
import com.dissonance.r2sync.data.repository.SyncRepository
import java.util.concurrent.TimeUnit

object R2SyncWorkScheduler {
    private const val TAG = "R2SyncWorkScheduler"

    fun scheduleImmediateDirtySync(context: Context, forceWifiOnly: Boolean = false) {
        try {
            val database = AppDatabase.getDatabase(context)
            val repository = SyncRepository(context, database)
            val energySettings = repository.loadEnergySettings()

            val requiredNetwork = if (forceWifiOnly || energySettings.syncOnlyOnWifi) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            }

            val constraintsBuilder = Constraints.Builder()
                .setRequiredNetworkType(requiredNetwork)

            if (energySettings.syncOnlyWhileCharging) {
                constraintsBuilder.setRequiresCharging(true)
            }
            if (energySettings.pauseOnLowBattery) {
                constraintsBuilder.setRequiresBatteryNotLow(true)
            }

            val workRequest = OneTimeWorkRequestBuilder<R2SyncWorker>()
                .setConstraints(constraintsBuilder.build())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.SECONDS
                )
                .addTag(R2SyncWorker.TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                R2SyncWorker.UNIQUE_DIRTY_SYNC_WORK,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            Log.d(TAG, "Scheduled immediate dirty sync work with network constraint: $requiredNetwork")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling dirty sync work", e)
        }
    }

    fun schedulePeriodicSync(context: Context, intervalMinutes: Long = 15) {
        try {
            val database = AppDatabase.getDatabase(context)
            val repository = SyncRepository(context, database)
            val energySettings = repository.loadEnergySettings()

            val interval = intervalMinutes.coerceAtLeast(15) // WorkManager min periodic interval is 15 mins

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (energySettings.syncOnlyOnWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(energySettings.pauseOnLowBattery)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<R2SyncWorker>(
                interval,
                TimeUnit.MINUTES,
                5,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(R2SyncWorker.TAG)
                .build()

            // UPDATE (not KEEP) so interval/constraint changes actually apply
            // to the already-enqueued work when settings change.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                R2SyncWorker.UNIQUE_PERIODIC_SYNC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicRequest
            )
            Log.d(TAG, "Scheduled periodic sync work every $interval minutes")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling periodic sync work", e)
        }
    }

    /**
     * Periodic FOLDER sync (SyncEngine passes) with an application-scoped
     * lifetime. Constraints mirror the user's energy settings.
     */
    fun scheduleFolderSync(
        context: Context,
        intervalMinutes: Long = 15,
        wifiOnly: Boolean = true,
        requireCharging: Boolean = false,
        batteryNotLow: Boolean = true
    ) {
        try {
            val interval = intervalMinutes.coerceAtLeast(15)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(requireCharging)
                .setRequiresBatteryNotLow(batteryNotLow)
                .build()

            val request = PeriodicWorkRequestBuilder<FolderSyncWorker>(
                interval,
                TimeUnit.MINUTES,
                5,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(FolderSyncWorker.TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                FolderSyncWorker.UNIQUE_FOLDER_SYNC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.d(TAG, "Scheduled periodic FOLDER sync every $interval minutes")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling folder sync work", e)
        }
    }

    fun cancelFolderSync(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(FolderSyncWorker.UNIQUE_FOLDER_SYNC_WORK)
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling folder sync work", e)
        }
    }

    fun cancelAllWork(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(R2SyncWorker.UNIQUE_DIRTY_SYNC_WORK)
            WorkManager.getInstance(context).cancelUniqueWork(R2SyncWorker.UNIQUE_PERIODIC_SYNC_WORK)
            WorkManager.getInstance(context).cancelUniqueWork(FolderSyncWorker.UNIQUE_FOLDER_SYNC_WORK)
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling work", e)
        }
    }
}
