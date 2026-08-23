package com.piercingxx.xxphone

import android.app.Application
import com.piercingxx.xxphone.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Boot glue only (design §12 Setup, §17 WS1): seed the `setting` defaults once,
 * then let ring/ create its channels. Nothing here may crash boot — both steps
 * are fire-and-forget and reconcile at point of use (D3: no alarms, no re-arm).
 */
class XxApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            runCatching {
                ServiceLocator.settings(this@XxApplication)
                    .ensureDefaults(SettingsRepository.designDefaults(System.currentTimeMillis()))
            }
            runCatching {
                ServiceLocator.channelRegistry(this@XxApplication).ensureAll()
            }
        }
    }
}
