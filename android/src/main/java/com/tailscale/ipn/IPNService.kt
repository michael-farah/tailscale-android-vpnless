// Modified for VPNless root daemon operation (Milestone 2+3)
// IPNService no longer extends VpnService; it delegates to the root tailscaled.
package com.tailscale.ipn

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * IPNService manages the Tailscale connection by controlling the root daemon.
 * In VPNless mode, no Android VpnService is used. Instead, this service
 * communicates with the magisk/KernelSU-launched tailscaled via root shell.
 */
open class IPNService : Service() {
  companion object {
    const val ACTION_START_VPN = "com.tailscale.ipn.START_VPN"
    const val ACTION_STOP_VPN = "com.tailscale.ipn.STOP_VPN"
    const val ACTION_RESTART_VPN = "com.tailscale.ipn.RESTART_VPN"
    const val ACTION_START_FOREGROUND_ONLY = "com.tailscale.ipn.START_FOREGROUND_ONLY"
  }

  private val TAG = "IPNService"
  private val randomID: String = UUID.randomUUID().toString()
  private lateinit var rootManager: RootTailscaleManager
  private lateinit var statusPoller: DaemonStatusPoller
  val scope = CoroutineScope(Dispatchers.IO)
  private var closed = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    rootManager = RootTailscaleManager(this)
    statusPoller = DaemonStatusPoller(rootManager)
    statusPoller.start(scope)
  }

  fun id(): String = randomID

  fun updateVpnStatus(status: Boolean) {
    App.get().getAppScopedViewModel().setVpnActive(status)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
      when (intent?.action) {
        ACTION_STOP_VPN -> {
          scope.launch {
            rootManager.stopTailscale()
            close()
          }
          START_NOT_STICKY
        }
        ACTION_RESTART_VPN -> {
          scope.launch {
            rootManager.stopTailscale()
            delay(500)
            val ok = rootManager.startTailscale()
            if (ok) updateVpnStatus(true)
          }
          START_NOT_STICKY
        }
        ACTION_START_FOREGROUND_ONLY -> {
          showForegroundNotification()
          START_NOT_STICKY
        }
        ACTION_START_VPN -> {
          showForegroundNotification()
          scope.launch {
            val ok = rootManager.startTailscale()
            if (ok) updateVpnStatus(true)
          }
          START_STICKY
        }
        "android.net.VpnService" -> {
          // Always On VPN trigger — ignored in VPNless mode
          START_NOT_STICKY
        }
        else -> START_NOT_STICKY
      }

  fun close() {
    if (closed) return
    closed = true
    Notifier.setState(Ipn.State.Stopping)
    disconnectVPN()
  }

  fun disconnectVPN() {
    stopSelf()
  }

  override fun onDestroy() {
    close()
    statusPoller.stop()
    updateVpnStatus(false)
    super.onDestroy()
  }

  private fun showForegroundNotification(
      hideDisconnectAction: Boolean = false,
      exitNodeName: String? = null
  ) {
    try {
      startForeground(
          UninitializedApp.STATUS_NOTIFICATION_ID,
          UninitializedApp.get().buildStatusNotification(true, hideDisconnectAction, exitNodeName))
    } catch (e: Exception) {
      TSLog.e(TAG, "Failed to start foreground service: $e")
    }
  }
}
