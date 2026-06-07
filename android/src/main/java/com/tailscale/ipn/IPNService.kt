// Modified for VPNless root daemon operation (Milestone 2+3)
// IPNService no longer extends VpnService; it delegates to the root tailscaled.
package com.tailscale.ipn

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import libtailscale.ParcelFileDescriptor
import java.util.UUID

/**
 * IPNService manages the Tailscale connection by controlling the root daemon.
 * In VPNless mode, the Go side still needs to see an IPNService, but
 * NewBuilder/Protect are no-ops because no Android VpnService Builder is used.
 */
open class IPNService : Service(), libtailscale.IPNService {
  private val TAG = "IPNService"
  private val randomID: String = UUID.randomUUID().toString()
  private lateinit var rootManager: RootTailscaleManager
  val scope = CoroutineScope(Dispatchers.IO)
  private var closed = false

  override fun id(): String = randomID

  /** no-op in VPNless mode */
  override fun Protect(fd: Int): Boolean = true

  /** no-op in VPNless mode — no Android VPN builder */
  override fun NewBuilder(): libtailscale.VPNServiceBuilder {
    return StubVPNServiceBuilder()
  }

  override fun Close() {
    close()
  }

  override fun DisconnectVPN() {
    disconnectVPN()
  }

  override fun UpdateVpnStatus(status: Boolean) {
    App.get().getAppScopedViewModel().setVpnActive(status)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    rootManager = RootTailscaleManager(this)
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
            if (ok) UpdateVpnStatus(true)
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
            if (ok) UpdateVpnStatus(true)
          }
          START_STICKY
        }
        "android.net.VpnService" -> {
          // Always On VPN — ignored in VPNless mode
          START_NOT_STICKY
        }
        else -> START_NOT_STICKY
      }

  override fun close() {
    if (closed) return
    closed = true
    Notifier.setState(Ipn.State.Stopping)
    disconnectVPN()
  }

  override fun disconnectVPN() {
    stopSelf()
  }

  override fun onDestroy() {
    close()
    UpdateVpnStatus(false)
    super.onDestroy()
  }

  override fun onRevoke() {
    close()
    UpdateVpnStatus(false)
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

  companion object {
    const val ACTION_START_VPN = "com.tailscale.ipn.START_VPN"
    const val ACTION_STOP_VPN = "com.tailscale.ipn.STOP_VPN"
    const val ACTION_RESTART_VPN = "com.tailscale.ipn.RESTART_VPN"
    const val ACTION_START_FOREGROUND_ONLY = "com.tailscale.ipn.START_FOREGROUND_ONLY"
  }
}

/** Stub builder used when IPNService is no-op for VPN construction. */
class StubVPNServiceBuilder : libtailscale.VPNServiceBuilder {
  override fun addAddress(p0: String, p1: Int) {}
  override fun addDNSServer(p0: String) {}
  override fun addRoute(p0: String, p1: Int) {}
  override fun excludeRoute(p0: String, p1: Int) {}
  override fun addSearchDomain(p0: String) {}
  override fun establish(): libtailscale.ParcelFileDescriptor? = null
  override fun setMTU(p0: Int) {}
}
