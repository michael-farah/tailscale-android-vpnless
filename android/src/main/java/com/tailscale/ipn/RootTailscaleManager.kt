// vpnless-root - Root-level controller for magisk-tailscale daemon
// Replaces VpnService-based engine with shell/socket commands to root tailscaled
package com.tailscale.ipn

import android.content.Context
import android.util.Log
import com.tailscale.ipn.ui.model.IpnState
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class RootTailscaleManager(private val context: Context) {
  companion object {
    const val TAG = "RootTailscaleManager"
    const val TAILSCALE_BIN = "/data/adb/tailscale/bin/tailscale"
    const val TAILSCALED_SOCK = "/data/adb/tailscale/tailscaled.sock"
    const val POLICY_TABLE = 105
    const val POLICY_PREF = 10000
  }

  init {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
  }

  /** Check if the root daemon is reachable */
  suspend fun isDaemonRunning(): Boolean = withContext(Dispatchers.IO) {
    try {
      val result = Shell.cmd("$TAILSCALE_BIN status 2>/dev/null").exec()
      result.isSuccess
    } catch (e: Exception) {
      Log.w(TAG, "isDaemonRunning error: $e")
      false
    }
  }

  /** Start the Tailscale network (tailscale up) */
  suspend fun startTailscale(): Boolean = withContext(Dispatchers.IO) {
    try {
      val result = Shell.cmd("$TAILSCALE_BIN up --accept-dns=false 2>&1").exec()
      if (!result.isSuccess) {
        Log.e(TAG, "tailscale up failed: " + result.out.joinToString("\n"))
      }
      result.isSuccess
    } catch (e: Exception) {
      Log.e(TAG, "startTailscale error: $e")
      false
    }
  }

  /** Stop the Tailscale network (tailscale down) */
  suspend fun stopTailscale(): Boolean = withContext(Dispatchers.IO) {
    try {
      val result = Shell.cmd("$TAILSCALE_BIN down 2>&1").exec()
      if (!result.isSuccess) {
        Log.e(TAG, "tailscale down failed: " + result.out.joinToString("\n"))
      }
      result.isSuccess
    } catch (e: Exception) {
      Log.e(TAG, "stopTailscale error: $e")
      false
    }
  }

  /** Fetch raw JSON status from the daemon (tailscale status --json) */
  suspend fun getStatusJson(): String? = withContext(Dispatchers.IO) {
    try {
      val result = Shell.cmd("$TAILSCALE_BIN status --json 2>/dev/null").exec()
      if (result.isSuccess) {
        result.out.joinToString("\n")
      } else null
    } catch (e: Exception) {
      Log.w(TAG, "getStatusJson error: $e")
      null
    }
  }

  private val statusDecoder = Json { ignoreUnknownKeys = true }

  /** Fetch parsed status from the daemon */
  suspend fun getStatus(): IpnState.Status? = withContext(Dispatchers.IO) {
    val json = getStatusJson() ?: return@withContext null
    try {
      statusDecoder.decodeFromString<IpnState.Status>(json)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to parse status JSON: $e")
      null
    }
  }

  /** Check root is available */
  suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
    Shell.getShell().isRoot
  }

  // ============================================================
  // Native routing: ip rule / ip route based on app UIDs
  // ============================================================

  /** Add routing for a single UID */
  suspend fun addUidRouting(uid: Int) {
    addUidRangeRouting(uid, uid)
  }

  /** Add routing for a UID range */
  suspend fun addUidRangeRouting(uidStart: Int, uidEnd: Int) {
    withContext(Dispatchers.IO) {
      Shell.cmd("ip route add default dev tailscale0 table $POLICY_TABLE 2>/dev/null || true").exec()
      Shell.cmd("ip rule add uidrange $uidStart-$uidEnd lookup $POLICY_TABLE pref $POLICY_PREF 2>/dev/null || true").exec()
      Shell.cmd("ip route flush cache").exec()
    }
  }

  /** Remove routing rules for a UID range */
  suspend fun removeUidRangeRouting(uidStart: Int, uidEnd: Int) {
    withContext(Dispatchers.IO) {
      Shell.cmd("ip rule del uidrange $uidStart-$uidEnd lookup $POLICY_TABLE pref $POLICY_PREF 2>/dev/null || true").exec()
      Shell.cmd("ip route flush cache").exec()
    }
  }

  /** Clear all vpnless policy rules */
  suspend fun clearAllRoutingRules() {
    withContext(Dispatchers.IO) {
      Shell.cmd("ip rule del pref $POLICY_PREF 2>/dev/null || true").exec()
      Shell.cmd("ip route flush table $POLICY_TABLE 2>/dev/null || true").exec()
      Shell.cmd("ip route flush cache").exec()
    }
  }

  /** Apply policy routing for selected packages */
  suspend fun applySplitTunneling(
    selectedPackages: List<String>,
    allInstalledPackages: List<String>,
    allowMode: Boolean
  ) {
    withContext(Dispatchers.IO) {
      clearAllRoutingRules()

      // Add default route to the custom routing table
      Shell.cmd("ip route add default dev tailscale0 table $POLICY_TABLE 2>/dev/null || true").exec()

      val pm = context.packageManager
      val uids = if (allowMode) {
        // Whitelist: only selected apps go through tailscale0
        selectedPackages.mapNotNull { pkg ->
          try {
            pm.getApplicationInfo(pkg, 0).uid
          } catch (_: Exception) { null }
        }
      } else {
        // Blacklist: everything EXCEPT selected goes through tailscale0
        val disallowed = selectedPackages.toSet()
        allInstalledPackages.filter { it !in disallowed }.mapNotNull { pkg ->
          try {
            pm.getApplicationInfo(pkg, 0).uid
          } catch (_: Exception) { null }
        }
      }

      // Apply rules per unique UID
      uids.distinct().forEach { uid ->
        Shell.cmd("ip rule add uidrange $uid-$uid lookup $POLICY_TABLE pref $POLICY_PREF 2>/dev/null || true").exec()
      }

      Shell.cmd("ip route flush cache").exec()
    }
  }
}
