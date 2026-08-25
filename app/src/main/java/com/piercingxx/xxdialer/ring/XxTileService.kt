package com.piercingxx.xxdialer.ring

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.piercingxx.xxdialer.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * "Expecting a call" QS tile (§7.1, D15). Tap toggles a BOUNDED bypass:
 * active ⇒ clear it, idle ⇒ now + the configured duration. There is no
 * indefinite variant — the setting cannot express one (D15).
 *
 * Label carries the live countdown ("Expecting · 1h59m"); a minute ticker
 * re-renders while listening and is cancelled in [onStopListening].
 */
class XxTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        refresh()
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                refresh()
            }
        }
    }

    override fun onStopListening() {
        ticker?.cancel()
        ticker = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        scope.launch { runCatching { toggle() }.onFailure { refresh() } }
    }

    // ---- internals -----------------------------------------------------------

    private suspend fun toggle() {
        val settings = ServiceLocator.settings(applicationContext)
        val now = System.currentTimeMillis()
        val until = runCatching { settings.bypassUntilMillis() }.getOrNull()
        if (TileCountdown.isActive(until, now)) {
            runCatching { settings.setBypassUntil(null) } // §7.1: tap again to end early
        } else {
            val minutes = runCatching { settings.bypassDurationMinutes() }.getOrDefault(DEFAULT_MINUTES)
            runCatching { settings.setBypassUntil(now + minutes * 60_000L) } // D15: bounded only
        }
        refresh()
    }

    /** Reflects current state; safe before the system attaches a tile. */
    private fun refresh() {
        val tile = qsTile ?: return
        val context = applicationContext
        scope.launch {
            val until = runCatching { ServiceLocator.settings(context).bypassUntilMillis() }.getOrNull()
            render(tile, until, System.currentTimeMillis())
        }
    }

    private fun render(tile: Tile, bypassUntilMillis: Long?, nowMillis: Long) {
        tile.label = TileCountdown.label(bypassUntilMillis, nowMillis)
        tile.state =
            if (TileCountdown.isActive(bypassUntilMillis, nowMillis)) Tile.STATE_ACTIVE
            else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    private companion object {
        const val TICK_MILLIS = 60_000L // countdown granularity is the minute
        const val DEFAULT_MINUTES = 120 // mirrors SettingsRepository's shipped default
    }
}
