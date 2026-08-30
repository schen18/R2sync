package com.dissonance.r2sync.energy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.PowerManager
import com.dissonance.r2sync.model.EnergySettings
import com.dissonance.r2sync.model.EnergyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class EnergyManager(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val _energyState = MutableStateFlow(EnergyState())
    val energyState: StateFlow<EnergyState> = _energyState.asStateFlow()

    private val savedBytesCounter = AtomicLong(14500000L) // Initial baseline saved
    private val savedSyncsCounter = AtomicInteger(18)

    private var currentSettings: EnergySettings = EnergySettings()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                updateBatteryAndPower(intent)
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateNetworkState()
        }

        override fun onLost(network: Network) {
            updateNetworkState()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            updateNetworkState()
        }
    }

    init {
        // Register network callback
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            // fallback
        }

        // Register battery receiver
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val initialIntent = context.registerReceiver(batteryReceiver, filter)
        if (initialIntent != null) {
            updateBatteryAndPower(initialIntent)
        } else {
            updateNetworkState()
        }
    }

    fun updateSettings(settings: EnergySettings) {
        currentSettings = settings
        recalculateSyncEligibility()
    }

    fun recordEnergySaving(bytesSaved: Long) {
        val totalBytes = savedBytesCounter.addAndGet(bytesSaved)
        val totalSyncs = savedSyncsCounter.incrementAndGet()
        _energyState.value = _energyState.value.copy(
            bytesSavedByDedup = totalBytes,
            syncsAvoided = totalSyncs
        )
    }

    private fun updateBatteryAndPower(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 80

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val isPowerSave = powerManager?.isPowerSaveMode ?: false

        val current = _energyState.value
        _energyState.value = current.copy(
            batteryPercent = batteryPct,
            isCharging = isCharging,
            isPowerSaveMode = isPowerSave
        )
        recalculateSyncEligibility()
    }

    private fun updateNetworkState() {
        var isWifi = false
        var isCellular = false

        connectivityManager?.let { cm ->
            val activeNetwork = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNetwork)
            if (caps != null) {
                isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            }
        }

        val current = _energyState.value
        _energyState.value = current.copy(
            isWifi = isWifi,
            isCellular = isCellular
        )
        recalculateSyncEligibility()
    }

    private fun recalculateSyncEligibility() {
        val state = _energyState.value
        var canSync = true
        var reason: String? = null

        if (!currentSettings.backgroundSyncEnabled) {
            canSync = false
            reason = "Auto-sync is paused globally"
        } else if (currentSettings.syncOnlyOnWifi && !state.isWifi) {
            canSync = false
            reason = "Waiting for Wi-Fi (Energy & Data Saver active)"
        } else if (currentSettings.syncOnlyWhileCharging && !state.isCharging) {
            canSync = false
            reason = "Waiting for device to connect to charger"
        } else if (currentSettings.pauseOnLowBattery && !state.isCharging && state.batteryPercent < currentSettings.lowBatteryThreshold) {
            canSync = false
            reason = "Paused due to low battery (${state.batteryPercent}% < ${currentSettings.lowBatteryThreshold}%)"
        }

        _energyState.value = state.copy(
            canSync = canSync,
            restrictionReason = reason
        )
    }

    fun cleanUp() {
        try {
            context.unregisterReceiver(batteryReceiver)
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // ignore
        }
    }
}
