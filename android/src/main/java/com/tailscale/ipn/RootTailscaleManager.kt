// vpnless-root - Root-level controller for magisk-tailscale daemon
// Replaces VpnService-based engine with shell/socket commands to root tailscaled
//
// Module Auto-Detection:
// - If the magisk-tailscaled module (anasfanani/magisk-tailscaled) is installed,
//   its binaries at /data/adb/tailscale/bin/ are preferred and the app skips
//   downloading & starting its own daemon.
// - The socket path is auto-resolved by trying well-known locations (module,
//   root CWD, app data dir) and falling back to no --socket flag (CLI default).
package com.tailscale.ipn

import android.content.Context
import android.util.Log
import com.tailscale.ipn.ui.model.IpnState
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class RootTailscaleManager(private val context: Context) {
  companion object {
    const val TAG = "RootTailscaleManager"

    // ── Module paths (anasfanani/magisk-tailscaled) ──────────────
    // Binary: /data/adb/tailscale/bin/tailscale (symlink to tailscaled)
    // Socket: /data/adb/tailscale/tailscaled.sock  (confirmed by user tree)
    // State:  /data/adb/tailscale/tailscaled.state
    // The module also creates /dev/tailscale → /data/adb/tailscale/bin/tailscale
    // and daemon is started with: tailscaled -no-logs-no-support (no --socket)
    const val MODULE_BIN = "/data/adb/tailscale/bin/tailscale"
    const val MODULE_SOCK = "/data/adb/tailscale/tailscaled.sock"

    // ── App-managed paths (standalone auto-install) ─────────────
    const val APP_BIN_DIR = "/data/data/com.tailscale.ipn/files/tailscale"
    const val APP_BIN = "$APP_BIN_DIR/tailscale"
    const val APP_SOCK = "$APP_BIN_DIR/tailscaled.sock"

    // ── Well-known binary search paths (in priority order) ──────
    private val BIN_CANDIDATES = listOf(
      MODULE_BIN,              // module binary
      "/dev/tailscale",       // module symlink
      APP_BIN,                 // app's auto-installed binary
      "/system/bin/tailscale",
      "/system/xbin/tailscale",
      "/data/adb/ksu/bin/tailscale",
    )

    // ── Well-known socket paths (tried in order, picks first) ───
    private val SOCKET_CANDIDATES = listOf(
      MODULE_SOCK,             // module socket (confirmed at this path!)
      APP_SOCK,                // app-managed daemon
      "/tailscaled.sock",     // CWD=/ (Magisk init context)
    )

    // ── KernelSU ╱ routing constants ────────────────────────────

    const val POLICY_TABLE = 105
    const val POLICY_PREF = 10000
  }

  /** Cached module detection result */
  private var moduleDetectedCache: Boolean? = null

  /** Cached resolved binary path */
  private var resolvedBinCache: String? = null

  /** Cached resolved socket path (null = use CLI default, no --socket flag) */
  private var resolvedSockCache: String? = null

  // ── Module Detection ──────────────────────────────────────────────────

  /** Check if the magisk-tailscaled module is installed and its binary works */
  suspend fun detectModule(): Boolean = withContext(Dispatchers.IO) {
    moduleDetectedCache?.let { return@withContext it }
    try {
      val out = suExec("test -x $MODULE_BIN && echo ok")
      val detected = out.contains("ok")
      moduleDetectedCache = detected
      if (detected) Log.i(TAG, "magisk-tailscaled module detected at $MODULE_BIN")
      detected
    } catch (_: Exception) {
      moduleDetectedCache = false
      false
    }
  }

  /** Invalidate cached detection so next call re-checks */
  fun invalidateCache() {
    moduleDetectedCache = null
    resolvedBinCache = null
    resolvedSockCache = null
  }

  // ── Binary Resolution ─────────────────────────────────────────────────

  /** Find the best available tailscale binary */
  suspend fun findTailscaleBinary(): String? = withContext(Dispatchers.IO) {
    resolvedBinCache?.let { return@withContext it }

    // If module detected, check that first
    if (detectModule()) {
      val out = suExec("test -x $MODULE_BIN && echo ok")
      if (out.contains("ok")) {
        Log.d(TAG, "Using module tailscale binary: $MODULE_BIN")
        resolvedBinCache = MODULE_BIN
        return@withContext MODULE_BIN
      }
    }

    // Check all known paths in priority order
    for (path in BIN_CANDIDATES) {
      val out = suExec("test -x $path && echo ok")
      if (out.contains("ok")) {
        Log.d(TAG, "Using tailscale binary: $path")
        resolvedBinCache = path
        return@withContext path
      }
    }

    // Fallback: try PATH lookup
    val whichOut = suExec("command -v tailscale 2>/dev/null || which tailscale 2>/dev/null")
    if (whichOut.isNotEmpty() && !whichOut.contains("not found")) {
      val path = whichOut.lines().first().trim()
      Log.d(TAG, "Using tailscale binary from PATH: $path")
      resolvedBinCache = path
      return@withContext path
    }

    Log.w(TAG, "No tailscale binary found anywhere")
    null
  }

  /** Get the resolved binary path, or null if none found */
  suspend fun getTailscalePath(): String? = findTailscaleBinary()

  /** True if we have a usable tailscale binary */
  suspend fun isTailscaleInstalled(): Boolean = findTailscaleBinary() != null

  // ── Socket Resolution ──────────────────────────────────────────────────

  /**
   * Resolve the daemon socket path.
   * Tries known socket locations in priority order.
   * Returns null if none found — caller should run without --socket flag.
   */
  suspend fun resolveSocketPath(): String? = withContext(Dispatchers.IO) {
    resolvedSockCache?.let { return@withContext it }

    for (sock in SOCKET_CANDIDATES) {
      val out = suExec("test -S $sock && echo ok")
      if (out.contains("ok")) {
        Log.d(TAG, "Using socket: $sock")
        resolvedSockCache = sock
        return@withContext sock
      }
    }

    // Fallback: try connecting without --socket
    Log.w(TAG, "No socket found at known paths, trying default CLI socket")
    resolvedSockCache = null
    null
  }

  /** True if the daemon socket is reachable */
  suspend fun isDaemonReachable(): Boolean = withContext(Dispatchers.IO) {
    try {
      val output = tailscaleCmd("status --json 2>/dev/null", null)
      output.isNotEmpty() && !output.contains("Failed to connect") && !output.contains("connect: no such file")
    } catch (_: Exception) { false }
  }

  // ── Shell Execution ───────────────────────────────────────────────────

  /** Run a shell command via su, returning combined stdout+stderr */
  private suspend fun suExec(command: String): String = withContext(Dispatchers.IO) {
    try {
      val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
      val output = proc.inputStream.bufferedReader().readText()
      val err = proc.errorStream.bufferedReader().readText()
      proc.waitFor()
      (output + err).trim()
    } catch (e: Exception) {
      Log.e(TAG, "suExec error for [${command.take(100)}]: $e")
      ""
    }
  }

  /** Build a tailscale CLI command with optional socket and timeout */
  private suspend fun tailscaleCmd(cmd: String, sock: String?): String {
    val bin = findTailscaleBinary() ?: return ""
    val sockArg = if (sock != null) "--socket $sock" else ""
    val fullCmd = if (sockArg.isNotEmpty()) "$bin $sockArg $cmd 2>&1" else "$bin $cmd 2>&1"
    return suExec(fullCmd)
  }

  /** Run tailscale command with timeout and auto-resolved socket */
  private suspend fun tailscaleCmd(cmd: String, timeoutSec: Int = 0): String {
    val sock = resolveSocketPath()
    val timeout = if (timeoutSec > 0) "timeout $timeoutSec" else ""
    val bin = findTailscaleBinary() ?: return ""
    val sockArg = if (sock != null) "--socket $sock" else ""

    val fullCmd = buildString {
      if (timeout.isNotEmpty()) append("$timeout ")
      append(bin)
      if (sockArg.isNotEmpty()) append(" $sockArg")
      append(" $cmd 2>&1")
      if (timeout.isNotEmpty()) append(" || true")
    }
    return suExec(fullCmd)
  }

  /** Run a raw tailscale subcommand with 30s timeout (waits for daemonReady) */
  suspend fun runTailscaleSubcommand(subcommand: String): String {
    kotlinx.coroutines.withTimeoutOrNull(120_000L) {
      com.tailscale.ipn.ui.localapi.Client.daemonReady.await()
    }
    return tailscaleCmd(subcommand, timeoutSec = 30)
  }

  // ── Daemon Check ──────────────────────────────────────────────────────

  /** Check if the root daemon is reachable */
  suspend fun isDaemonRunning(): Boolean = isDaemonReachable()

  // ── Network Control ───────────────────────────────────────────────────

  /**
   * Start the Tailscale network (tailscale up).
   * Uses a 10-second timeout to prevent hanging.
   */
  suspend fun startTailscale(): Boolean = withContext(Dispatchers.IO) {
    try {
      val output = tailscaleCmd("up --accept-dns=false", timeoutSec = 10)
      if (output.contains("command not found") || output.contains("not found")) {
        Log.e(TAG, "tailscale binary not found")
        return@withContext false
      }
      if (output.contains("Error") && output.contains("connect")) {
        Log.e(TAG, "tailscale up failed (socket error): $output")
        return@withContext false
      }
      true
    } catch (e: Exception) {
      Log.e(TAG, "startTailscale error: $e")
      false
    }
  }

  /** Stop the Tailscale network (tailscale down) */
  suspend fun stopTailscale(): Boolean = withContext(Dispatchers.IO) {
    try {
      tailscaleCmd("down", timeoutSec = 5)
      true
    } catch (e: Exception) {
      Log.e(TAG, "stopTailscale error: $e")
      false
    }
  }

  // ── Status ────────────────────────────────────────────────────────────

  /** Fetch raw JSON status from the daemon */
  suspend fun getStatusJson(): String? = withContext(Dispatchers.IO) {
    try {
      tailscaleCmd("status --json")
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

  /** Check root is available using direct su test */
  suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
    try {
      val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo ok"))
      val output = proc.inputStream.bufferedReader().readText().trim()
      proc.waitFor()
      output == "ok"
    } catch (e: Exception) {
      false
    }
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
    suExec("ip route add default dev tailscale0 table $POLICY_TABLE 2>/dev/null || true")
    suExec("ip rule add uidrange $uidStart-$uidEnd lookup $POLICY_TABLE pref $POLICY_PREF 2>/dev/null || true")
    suExec("ip route flush cache")
  }

  /** Remove routing rules for a UID range */
  suspend fun removeUidRangeRouting(uidStart: Int, uidEnd: Int) {
    suExec("ip rule del uidrange $uidStart-$uidEnd lookup $POLICY_TABLE pref $POLICY_PREF 2>/dev/null || true")
    suExec("ip route flush cache")
  }

  /** Clear all vpnless policy rules */
  suspend fun clearAllRoutingRules() {
    suExec("ip rule del pref $POLICY_PREF 2>/dev/null || true")
    suExec("ip route flush table $POLICY_TABLE 2>/dev/null || true")
    suExec("ip route flush cache")
  }

  /** Apply policy routing for selected packages */
  suspend fun applySplitTunneling(
    selectedPackages: List<String>,
    allInstalledPackages: List<String>,
    allowMode: Boolean
  ) {
    clearAllRoutingRules()
    suExec("ip route add default dev tailscale0 table $POLICY_TABLE 2>/dev/null || true")

    val pm = context.packageManager
    val uids = if (allowMode) {
      selectedPackages.mapNotNull { pkg ->
        try { pm.getApplicationInfo(pkg, 0).uid } catch (_: Exception) { null }
      }
    } else {
      val disallowed = selectedPackages.toSet()
      allInstalledPackages.filter { it !in disallowed }.mapNotNull { pkg ->
        try { pm.getApplicationInfo(pkg, 0).uid } catch (_: Exception) { null }
      }
    }

    uids.distinct().forEach { uid ->
      suExec("ip rule add uidrange $uid-$uid lookup $POLICY_TABLE pref $POLICY_PREF 2>/dev/null || true")
    }
    suExec("ip route flush cache")
  }
}
