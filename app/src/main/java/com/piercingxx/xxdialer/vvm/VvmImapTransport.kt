package com.piercingxx.xxdialer.vvm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager

/**
 * Device-side inputs for [VvmImapPolicy.canSync]: whether the carrier requires
 * cellular data, and whether the active network is actually cellular. Missing
 * config or connectivity fails toward "not required" / "not on cellular" —
 * never invents a transport the device does not have.
 */
object VvmImapTransport {

    fun cellularDataRequired(context: Context): Boolean = runCatching {
        val subId = SubscriptionManager.getDefaultSubscriptionId()
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return@runCatching false
        val manager = context.getSystemService(CarrierConfigManager::class.java)
            ?: return@runCatching false
        val config = manager.getConfigForSubId(subId) ?: return@runCatching false
        // Literal key: KEY_VVM_CELLULAR_DATA_REQUIRED_BOOLEAN is not in every
        // compileSdk stub we hit, but the carrier bundle still uses this name.
        config.getBoolean("vvm_cellular_data_required_bool")
    }.getOrDefault(false)

    fun onCellularData(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        val network = cm.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }.getOrDefault(false)
}
