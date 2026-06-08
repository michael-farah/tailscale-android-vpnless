// VPNless - Daemon status bridge
// Polls the root tailscaled daemon status and feeds it into the app's Notifier.
package com.tailscale.ipn

import com.tailscale.ipn.ui.model.Health
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnState
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * DaemonStatusPoller periodically polls the root tailscaled daemon's status
 * and bridges it into the app's Notifier StateFlows for UI consumption.
 *
 * This replaces the in-process Go backend's push-based notification watcher
 * with a polling approach suitable for VPNless root daemon operation.
 */
class DaemonStatusPoller(private val rootManager: RootTailscaleManager) {
  companion object {
    private const val TAG = "DaemonStatusPoller"
    private const val POLL_INTERVAL_MS = 2000L
    private const val POLL_INTERVAL_FAST_MS = 500L
    private const val MAX_STARTUP_POLLS = 30 // 15 seconds at 500ms

    // Mapping from tailscale BackendState strings to Ipn.State values
    private val backendStateMap: Map<String, Ipn.State> = mapOf(
      "NoState" to Ipn.State.NoState,
      "InUseOtherUser" to Ipn.State.InUseOtherUser,
      "NeedsLogin" to Ipn.State.NeedsLogin,
      "NeedsMachineAuth" to Ipn.State.NeedsMachineAuth,
      "Stopped" to Ipn.State.Stopped,
      "Starting" to Ipn.State.Starting,
      "Running" to Ipn.State.Running,
    )
  }

  private var pollJob: Job? = null
  private var isStarted = false

  /** Current known state for change detection */
  private var lastState: Ipn.State = Ipn.State.NoState
  private var lastBackendState: String? = null

  /** Start polling the daemon status */
  fun start(scope: CoroutineScope) {
    if (isStarted) return
    isStarted = true
    TSLog.d(TAG, "Starting daemon status poller")

    pollJob = scope.launch(Dispatchers.IO) {
      // Fast-poll during startup to catch state transitions quickly
      var fastPolls = 0
      var useFastPoll = true

      while (isActive) {
        try {
          val status = rootManager.getStatus()
          if (status != null) {
            bridgeStatus(status)
          } else {
            // Daemon not reachable - set stopped state
            if (lastState != Ipn.State.Stopped) {
              TSLog.d(TAG, "Daemon not reachable, setting Stopped")
              Notifier.setState(Ipn.State.Stopped)
              lastState = Ipn.State.Stopped
            }
          }

          // Switch to slow polling once we reach a steady state
          if (useFastPoll) {
            fastPolls++
            if (lastState == Ipn.State.Running || lastState == Ipn.State.Stopped || fastPolls >= MAX_STARTUP_POLLS) {
              useFastPoll = false
              TSLog.d(TAG, "Switched to slow polling (state=$lastState)")
            }
          }
        } catch (e: Exception) {
          TSLog.e(TAG, "Poll error: ${e.message}")
        }

        delay(if (useFastPoll) POLL_INTERVAL_FAST_MS else POLL_INTERVAL_MS)
      }
    }
  }

  /** Stop polling */
  fun stop() {
    isStarted = false
    pollJob?.cancel()
    pollJob = null
    TSLog.d(TAG, "Daemon status poller stopped")
  }

  /** Bridge daemon status into Notifier StateFlows */
  private fun bridgeStatus(status: IpnState.Status) {
    // 1. Map BackendState to Ipn.State
    val newState = backendStateMap[status.BackendState] ?: Ipn.State.NoState

    if (newState != lastState) {
      TSLog.d(TAG, "State change: ${lastState} -> ${newState} (backend=\"${status.BackendState}\")")
      Notifier.setState(newState)
      lastState = newState
    }

    // 2. Update engine status (approximate from peers)
    if (status.Peer != null) {
      val livePeers = status.Peer.values.filter { it.Online }
      val rBytes = status.Peer.values.sumOf { it.RxBytes }
      val wBytes = status.Peer.values.sumOf { it.TxBytes }
      val engineStatus = Ipn.EngineStatus(
        RBytes = rBytes,
        WBytes = wBytes,
        NumLive = livePeers.size,
        LivePeers = livePeers.associate { it.PublicKey to
          IpnState.PeerStatusLite(
            RxBytes = it.RxBytes,
            TxBytes = it.TxBytes,
            LastHandshake = it.LastSeen ?: "",
            NodeKey = it.PublicKey
          )
        }
      )
      Notifier.engineStatus.set(engineStatus)
    }

    // 3. Update health — tailscale status --json returns Health as List<String>
    status.Health?.let { healthWarnings ->
      if (healthWarnings.isNotEmpty()) {
        val warningsMap = healthWarnings.mapIndexed { idx, msg ->
          // Create minimal UnhealthyState from warning string
          // Full structured parsing (WarnableCode, Severity, etc.) requires the Go backend's
          // structured health format. For the root daemon bridge, we map plain strings.
          "daemon-warning-$idx" to Health.UnhealthyState(
            WarnableCode = "daemon-health",
            Severity = Health.Severity.medium,
            Title = msg.ifBlank { "Daemon health warning" },
            Text = msg,
            ImpactsConnectivity = msg.contains("not connected", ignoreCase = true),
          )
        }.toMap()
        Notifier.health.set(Health.State(Warnings = warningsMap))
      } else {
        Notifier.health.set(null)
      }
    }

    // 4. Update version
    Notifier.version.set(status.Version)

    // 5. Build minimal netmap if self data is available
    status.Self?.let { self ->
      buildNetmap(status, self)
    }
  }

  /** Build a minimal NetworkMap from daemon status */
  private fun buildNetmap(status: IpnState.Status, self: IpnState.PeerStatus) {
    val magicDNSSuffix = status.CurrentTailnet?.MagicDNSSuffix ?: ""

    val selfNode = Tailcfg.Node(
      ID = self.NodeID,
      StableID = self.ID,
      Name = self.DNSName.ifEmpty { self.HostName },
      User = self.UserID,
      Key = self.PublicKey,
      Addresses = self.TailscaleIPs,
      AllowedIPs = self.AllowedIPs,
      Hostinfo = Tailcfg.Hostinfo(
        OS = self.OS,
        Hostname = self.HostName,
        ShareeNode = self.ShareeNode ?: false,
        Location = self.Location
      ),
      Online = true,
      LastSeen = self.LastSeen,
      ComputedName = self.HostName.ifEmpty { self.PublicKey },
      ComputedNameWithHost = if (self.HostName.isNotEmpty()) self.HostName else self.PublicKey,
    )

    val peers = status.Peer?.values?.map { peerStatus ->
      Tailcfg.Node(
        ID = peerStatus.NodeID,
        StableID = peerStatus.ID,
        Name = peerStatus.DNSName.ifEmpty { peerStatus.HostName },
        User = peerStatus.UserID,
        Key = peerStatus.PublicKey,
        Addresses = peerStatus.TailscaleIPs,
        AllowedIPs = peerStatus.AllowedIPs,
        Hostinfo = Tailcfg.Hostinfo(
          OS = peerStatus.OS,
          Hostname = peerStatus.HostName,
          ShareeNode = peerStatus.ShareeNode ?: false,
          Location = peerStatus.Location
        ),
        Online = peerStatus.Online,
        LastSeen = peerStatus.LastSeen,
        ComputedName = peerStatus.HostName.ifEmpty { peerStatus.PublicKey },
        ComputedNameWithHost = peerStatus.HostName.ifEmpty { peerStatus.PublicKey },
      )
    } ?: emptyList()

    val userProfiles = status.User?.mapValues { (_, profile) ->
      Tailcfg.UserProfile(
        ID = profile.ID,
        DisplayName = profile.DisplayName,
        LoginName = profile.LoginName,
        ProfilePicURL = profile.ProfilePicURL,
      )
    } ?: emptyMap()

    val netmap = Netmap.NetworkMap(
      SelfNode = selfNode,
      Peers = peers,
      Domain = status.CurrentTailnet?.Name ?: "",
      UserProfiles = userProfiles,
      TKAEnabled = false,
      DNS = null,
      AllCaps = emptyList(),
    )

    Notifier.netmap.set(netmap)
  }
}
