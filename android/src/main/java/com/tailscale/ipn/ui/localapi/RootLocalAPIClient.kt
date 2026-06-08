// VPNless - Root daemon local API client
// Replaces libtailscale.Application.callLocalAPI() with root shell CLI commands.
package com.tailscale.ipn.ui.localapi

import android.content.Context
import com.tailscale.ipn.RootTailscaleManager
import com.tailscale.ipn.ui.model.BugReportID
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.IpnState
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * RootLocalAPIClient communicates with the root tailscaled daemon via
 * `tailscale` CLI commands over root shell, replacing the in-process
 * libtailscale.Application.callLocalAPI() mechanism.
 *
 * This is used in VPNless mode where the root daemon handles all
 * networking and the app acts as a control/status interface.
 */
class RootLocalAPIClient(private val context: Context) {
  companion object {
    private const val TAG = "RootLocalAPIClient"
    private val jsonDecoder = Json { ignoreUnknownKeys = true }
  }

  private val rootManager: RootTailscaleManager by lazy { RootTailscaleManager(context) }

  /**
   * Start the Tailscale connection. In VPNless mode, this runs `tailscale up`.
   */
  fun start(options: Ipn.Options, responseHandler: (Result<Unit>) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val ok = rootManager.startTailscale()
        if (ok) {
          responseHandler(Result.success(Unit))
        } else {
          responseHandler(Result.failure(Exception("tailscale up failed")))
        }
      } catch (e: Exception) {
        TSLog.e(TAG, "start error: $e")
        responseHandler(Result.failure(e))
      }
    }
  }

  /**
   * Get daemon status. Delegates to RootTailscaleManager's getStatus().
   */
  fun status(responseHandler: (Result<IpnState.Status>) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val status = rootManager.getStatus()
        if (status != null) {
          responseHandler(Result.success(status))
        } else {
          responseHandler(Result.failure(Exception("Failed to get daemon status")))
        }
      } catch (e: Exception) {
        TSLog.e(TAG, "status error: $e")
        responseHandler(Result.failure(e))
      }
    }
  }

  /**
   * Get current preferences. Reads from `tailscale status --json` (which includes
   * some prefs). For full prefs, we'd need the local API, but this covers key fields.
   */
  fun prefs(responseHandler: (Result<Ipn.Prefs>) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val status = rootManager.getStatus()
        if (status != null) {
          // Build minimal Prefs from status data
          val prefs = Ipn.Prefs(
            WantRunning = status.BackendState == "Running" || status.BackendState == "Starting",
          )
          responseHandler(Result.success(prefs))
        } else {
          responseHandler(Result.success(Ipn.Prefs(WantRunning = false)))
        }
      } catch (e: Exception) {
        TSLog.e(TAG, "prefs error: $e")
        responseHandler(Result.failure(e))
      }
    }
  }

  /**
   * Edit preferences. Currently a subset — most prefs are set via `tailscale up` flags.
   * For full prefs editing, the daemon's local API socket should be used.
   */
  fun editPrefs(prefs: Ipn.MaskedPrefs, responseHandler: (Result<Ipn.Prefs>) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        // In VPNless mode, most prefs are set via `tailscale up --<flag>` arguments.
        // This is a simplified implementation; full prefs editing requires direct
        // socket communication with the daemon's local API.
        val wantRunning = prefs.WantRunning
        if (wantRunning != null) {
          if (wantRunning) {
            rootManager.startTailscale()
          } else {
            rootManager.stopTailscale()
          }
        }

        // Return current prefs as acknowledgment
        prefs { result ->
          responseHandler(result)
        }
      } catch (e: Exception) {
        TSLog.e(TAG, "editPrefs error: $e")
        responseHandler(Result.failure(e))
      }
    }
  }

  /**
   * Logout from Tailscale.
   */
  fun logout(responseHandler: (Result<String>) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val result = com.topjohnwu.superuser.Shell.cmd(
          "${RootTailscaleManager.TAILSCALE_BIN} logout 2>&1"
        ).exec()
        if (result.isSuccess) {
          responseHandler(Result.success(result.out.joinToString("\n")))
        } else {
          responseHandler(Result.failure(Exception("Logout failed: ${result.out.joinToString("\n")}")))
        }
      } catch (e: Exception) {
        TSLog.e(TAG, "logout error: $e")
        responseHandler(Result.failure(e))
      }
    }
  }

  /**
   * Ping a peer.
   */
  fun ping(peer: Tailcfg.Node, responseHandler: (Result<IpnState.PingResult>) -> Unit) {
    val ip = peer.primaryIPv4Address.orEmpty()
    if (ip.isEmpty()) {
      responseHandler(Result.failure(Exception("No IP address for peer $peer")))
      return
    }

    CoroutineScope(Dispatchers.IO).launch {
      try {
        val result = com.topjohnwu.superuser.Shell.cmd(
          "${RootTailscaleManager.TAILSCALE_BIN} ping --c 3 --timeout 5s $ip 2>&1"
        ).exec()
        val output = result.out.joinToString("\n")
        responseHandler(Result.success(
          IpnState.PingResult(
            IP = ip,
            Err = if (result.isSuccess) "" else output,
            LatencySeconds = parsePingLatency(output),
          )
        ))
      } catch (e: Exception) {
        TSLog.e(TAG, "ping error: $e")
        responseHandler(Result.failure(e))
      }
    }
  }

  /**
   * Start interactive login via the root daemon.
   * Captures the auth URL from 'tailscale login' output and feeds it
   * into the Notifier's browseToURL flow for UI display.
   */
  fun startLoginInteractive(responseHandler: (Result<Unit>) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val result = com.topjohnwu.superuser.Shell.cmd(
          "${RootTailscaleManager.TAILSCALE_BIN} login 2>&1"
        ).exec()

        if (result.isSuccess) {
          // Check if output contains a URL (auth URL)
          val authUrl = result.out.joinToString("\n").let { output ->
            Regex("""https://[^\s]+""").find(output)?.value
          }
          if (authUrl != null) {
            TSLog.d(TAG, "Login URL: $authUrl")
            Notifier.browseToURL.set(authUrl)
          }
          responseHandler(Result.success(Unit))
        } else {
          val err = result.out.joinToString("\n")
          TSLog.e(TAG, "Login failed: $err")
          responseHandler(Result.failure(Exception(err)))
        }
      } catch (e: Exception) {
        TSLog.e(TAG, "login error: $e")
        responseHandler(Result.failure(e))
      }
    }
  }

  /**
   * Get bug report ID.
   */
  fun bugReportId(responseHandler: (Result<BugReportID>) -> Unit) {
    responseHandler(Result.success(""))
  }

  /**
   * Get profiles list.
   */
  fun profiles(responseHandler: (Result<List<IpnLocal.LoginProfile>>) -> Unit) {
    // In VPNless mode, profile management is handled by the daemon.
    // Return an empty list for now.
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val status = rootManager.getStatus()
        val userProfiles = status?.User?.values?.map { profile ->
          IpnLocal.LoginProfile(
            ID = profile.ID.toString(),
            Name = profile.DisplayName.ifEmpty { profile.LoginName },
            Key = "",
            UserProfile = Tailcfg.UserProfile(
              ID = profile.ID,
              DisplayName = profile.DisplayName,
              LoginName = profile.LoginName,
              ProfilePicURL = profile.ProfilePicURL ?: "",
            ),
            LocalUserID = profile.ID.toString(),
          )
        } ?: emptyList()
        responseHandler(Result.success(userProfiles))
      } catch (e: Exception) {
        TSLog.e(TAG, "profiles error: $e")
        responseHandler(Result.success(emptyList()))
      }
    }
  }

  /**
   * Get current profile.
   */
  fun currentProfile(responseHandler: (Result<IpnLocal.LoginProfile>) -> Unit) {
    profiles { result ->
      result.fold(
        onSuccess = { profiles ->
          val current = profiles.firstOrNull()
          if (current != null) {
            responseHandler(Result.success(current))
          } else {
            responseHandler(Result.failure(Exception("No active profile")))
          }
        },
        onFailure = { responseHandler(Result.failure(it)) },
      )
    }
  }

  /**
   * Toggle exit node usage.
   */
  fun setUseExitNode(use: Boolean, responseHandler: (Result<Ipn.Prefs>) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        if (use) {
          // TBD: Get exit node from daemon status and set via tailscale up --exit-node=
          // For now, acknowledge the request
        }
        prefs(responseHandler)
      } catch (e: Exception) {
        TSLog.e(TAG, "setUseExitNode error: $e")
        responseHandler(Result.failure(e))
      }
    }
  }

  private fun parsePingLatency(output: String): Double {
    val pattern = Regex("""via\s+.*?\s+in\s+(\d+)ms""")
    return pattern.find(output)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
  }
}
