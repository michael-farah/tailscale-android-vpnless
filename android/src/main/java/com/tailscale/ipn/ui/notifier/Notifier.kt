// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Empty
import com.tailscale.ipn.ui.model.Health
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnState
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

/**
 * Notifier holds the StateFlows that reflect Tailscale's current state.
 *
 * In VPNless mode, the DaemonStatusPoller (started by IPNService) periodically
 * fetches status from the root tailscaled daemon and writes to these flows.
 *
 * In legacy mode, the in-process Go backend's push notification bus was used.
 */
object Notifier {
  private const val TAG = "Notifier"

  val jsonDecoder = Json { ignoreUnknownKeys = true }

  private val _state = MutableStateFlow(Ipn.State.NoState)
  val state: StateFlow<Ipn.State> = _state

  private val _netmap = MutableStateFlow<Netmap.NetworkMap?>(null)
  val netmap: StateFlow<Netmap.NetworkMap?> = _netmap

  val prefs: StateFlow<Ipn.Prefs?> = MutableStateFlow(null)
  val engineStatus: StateFlow<Ipn.EngineStatus?> = MutableStateFlow(null)
  val errMessage: StateFlow<String?> = MutableStateFlow(null)
  val tailFSShares: StateFlow<Map<String, String>?> = MutableStateFlow(null)
  val browseToURL: StateFlow<String?> = MutableStateFlow(null)
  val loginFinished: StateFlow<String?> = MutableStateFlow(null)
  val version: StateFlow<String?> = MutableStateFlow(null)
  val health: StateFlow<Health.State?> = MutableStateFlow(null)

  // Taildrop-specific State
  val outgoingFiles: StateFlow<List<Ipn.OutgoingFile>?> = MutableStateFlow(null)
  val incomingFiles: StateFlow<List<Ipn.PartialFile>?> = MutableStateFlow(null)
  val filesWaiting: StateFlow<Empty.Message?> = MutableStateFlow(null)

  // Legacy Go backend references (used only for non-VPNless fallback)
  private var app: Any? = null
  private var manager: Any? = null

  @Synchronized
  @JvmStatic
  fun setApp(newApp: Any?) {
    app = newApp
  }

  @Synchronized
  @OptIn(ExperimentalSerializationApi::class)
  fun start(scope: CoroutineScope) {
    TSLog.d(TAG, "Starting Notifier")

    // VPNless: the DaemonStatusPoller handles state bridging from the
    // root tailscaled daemon into the Notifier StateFlows.
    if (Client.useRootDaemon) {
      TSLog.d(TAG, "VPNless mode: state managed by DaemonStatusPoller")
      return
    }

    // Legacy mode (requires libtailscale AAR) is no longer supported.
    TSLog.d(TAG, "Legacy mode not supported without libtailscale")
  }

  fun stop() {
    TSLog.d(TAG, "Stopping Notifier")
    if (Client.useRootDaemon) {
      return
    }
    TSLog.d(TAG, "Legacy mode — no-op")
    manager = null
  }

  @JvmStatic
  fun setState(newState: Ipn.State) {
    _state.set(newState)
  }
}
