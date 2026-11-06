// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.localapi

import android.content.Context
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.model.BugReportID
import com.tailscale.ipn.ui.model.Errors
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.IpnState
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.util.TSLog
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

private object Endpoint {
  const val DEBUG = "debug"
  const val DEBUG_LOG = "debug-log"
  const val BUG_REPORT = "bugreport"
  const val PREFS = "prefs"
  const val FILE_TARGETS = "file-targets"
  const val UPLOAD_METRICS = "upload-client-metrics"
  const val START = "start"
  const val LOGIN_INTERACTIVE = "login-interactive"
  const val RESET_AUTH = "reset-auth"
  const val LOGOUT = "logout"
  const val PROFILES = "profiles/"
  const val PROFILES_CURRENT = "profiles/current"
  const val STATUS = "status"
  const val TKA_STATUS = "tka/status"
  const val TKA_SIGN = "tka/sign"
  const val TKA_VERIFY_DEEP_LINK = "tka/verify-deeplink"
  const val PING = "ping"
  const val FILES = "files"
  const val FILE_PUT = "file-put"
  const val TAILFS_SERVER_ADDRESS = "tailfs/fileserver-address"
  const val ENABLE_EXIT_NODE = "set-use-exit-node-enabled"
}

typealias StatusResponseHandler = (Result<IpnState.Status>) -> Unit

typealias TailnetLockStatusResponseHandler = (Result<IpnState.NetworkLockStatus>) -> Unit

typealias BugReportIdHandler = (Result<BugReportID>) -> Unit

typealias PrefsHandler = (Result<Ipn.Prefs>) -> Unit

typealias PingResultHandler = (Result<IpnState.PingResult>) -> Unit

/**
 * Client provides a mechanism for calling Tailscale's LocalAPI.
 *
 * In normal mode, it uses the in-process Go backend (libtailscale.Application.callLocalAPI()).
 * In VPNless mode (useRootDaemon = true), it delegates to RootLocalAPIClient which
 * communicates with the root tailscaled daemon via the 'tailscale' CLI.
 */
class Client(private val scope: CoroutineScope) {
  companion object {
    const val TAG = "Client"

    /** Set to true to use the root tailscaled daemon instead of in-process libtailscale */
    @JvmStatic
    var useRootDaemon: Boolean = false

    /**
     * Deferred that completes when the root tailscaled daemon is ready.
     * [login()] and other API calls await this before proceeding.
     */
    @JvmStatic
    val daemonReady: kotlinx.coroutines.CompletableDeferred<Unit> = kotlinx.coroutines.CompletableDeferred()

    /** Check whether the daemon is ready (non-blocking) */
    @JvmStatic
    fun isDaemonReady(): Boolean = daemonReady.isCompleted
  }

  private val TAG = Client::class.simpleName

  // Access libtailscale.Application lazily (only when not using root daemon)
  private val app: Any? by lazy {
    if (!useRootDaemon) App.get().getLibtailscaleApp() else null
  }

  // Root daemon client (only created when useRootDaemon)
  private var rootClient: RootLocalAPIClient? = null

  private fun getRootClient(context: Context? = null): RootLocalAPIClient? {
    if (!useRootDaemon) return null
    if (rootClient == null) {
      rootClient = RootLocalAPIClient(context ?: App.get())
    }
    return rootClient
  }

  fun start(options: Ipn.Options, responseHandler: (Result<Unit>) -> Unit) {
    if (useRootDaemon) {
      getRootClient()?.start(options, responseHandler)
      return
    }
    val body = Json.encodeToString(options).toByteArray()
    return post(Endpoint.START, body, responseHandler = responseHandler)
  }

  fun status(responseHandler: StatusResponseHandler) {
    if (useRootDaemon) {
      getRootClient()?.status(responseHandler)
      return
    }
    get(Endpoint.STATUS, responseHandler = responseHandler)
  }

  fun ping(peer: Tailcfg.Node, responseHandler: PingResultHandler) {
    if (useRootDaemon) {
      getRootClient()?.ping(peer, responseHandler)
      return
    }
    val ip = peer.primaryIPv4Address.orEmpty()
    if (ip.isEmpty()) {
      responseHandler(Result.failure(Exception("No IP address for peer $peer")))
      return
    }
    val path = "${Endpoint.PING}?ip=${ip}&type=disco"
    post(path, timeoutMillis = 2000L, responseHandler = responseHandler)
  }

  fun bugReportId(responseHandler: BugReportIdHandler) {
    if (useRootDaemon) {
      getRootClient()?.bugReportId(responseHandler)
      return
    }
    post(Endpoint.BUG_REPORT, responseHandler = responseHandler)
  }

  fun prefs(responseHandler: PrefsHandler) {
    if (useRootDaemon) {
      getRootClient()?.prefs(responseHandler)
      return
    }
    get(Endpoint.PREFS, responseHandler = responseHandler)
  }

  fun editPrefs(prefs: Ipn.MaskedPrefs, responseHandler: (Result<Ipn.Prefs>) -> Unit) {
    if (useRootDaemon) {
      getRootClient()?.editPrefs(prefs, responseHandler)
      return
    }
    val body = Json.encodeToString(prefs).toByteArray()
    return patch(Endpoint.PREFS, body, responseHandler = responseHandler)
  }

  fun setUseExitNode(use: Boolean, responseHandler: (Result<Ipn.Prefs>) -> Unit) {
    if (useRootDaemon) {
      getRootClient()?.setUseExitNode(use, responseHandler)
      return
    }
    val path = "${Endpoint.ENABLE_EXIT_NODE}?enabled=$use"
    return post(path, responseHandler = responseHandler)
  }

  fun profiles(responseHandler: (Result<List<IpnLocal.LoginProfile>>) -> Unit) {
    if (useRootDaemon) {
      getRootClient()?.profiles(responseHandler)
      return
    }
    get(Endpoint.PROFILES, responseHandler = responseHandler)
  }

  fun currentProfile(responseHandler: (Result<IpnLocal.LoginProfile>) -> Unit) {
    if (useRootDaemon) {
      getRootClient()?.currentProfile(responseHandler)
      return
    }
    return get(Endpoint.PROFILES_CURRENT, responseHandler = responseHandler)
  }

  fun addProfile(responseHandler: (Result<String>) -> Unit = {}) {
    if (useRootDaemon) {
      // Not supported via root daemon yet
      responseHandler(Result.success(""))
      return
    }
    return put(Endpoint.PROFILES, responseHandler = responseHandler)
  }

  fun deleteProfile(
      profile: IpnLocal.LoginProfile,
      responseHandler: (Result<String>) -> Unit = {}
  ) {
    if (useRootDaemon) {
      responseHandler(Result.success(""))
      return
    }
    return delete(Endpoint.PROFILES + profile.ID, responseHandler = responseHandler)
  }

  fun switchProfile(
      profile: IpnLocal.LoginProfile,
      responseHandler: (Result<String>) -> Unit = {}
  ) {
    if (useRootDaemon) {
      responseHandler(Result.success(""))
      return
    }
    return post(Endpoint.PROFILES + profile.ID, responseHandler = responseHandler)
  }

  fun startLoginInteractive(responseHandler: (Result<Unit>) -> Unit) {
    if (useRootDaemon) {
      getRootClient()?.startLoginInteractive(responseHandler)
      return
    }
    return post(Endpoint.LOGIN_INTERACTIVE, responseHandler = responseHandler)
  }

  fun logout(responseHandler: (Result<String>) -> Unit) {
    if (useRootDaemon) {
      getRootClient()?.logout(responseHandler)
      return
    }
    return post(Endpoint.LOGOUT, responseHandler = responseHandler)
  }

  fun tailnetLockStatus(responseHandler: TailnetLockStatusResponseHandler) {
    if (useRootDaemon) {
      responseHandler(Result.failure(Exception("TKA not yet supported in VPNless mode")))
      return
    }
    get(Endpoint.TKA_STATUS, responseHandler = responseHandler)
  }

  fun fileTargets(responseHandler: (Result<List<Ipn.FileTarget>>) -> Unit) {
    if (useRootDaemon) {
      responseHandler(Result.failure(Exception("Taildrop not yet supported in VPNless mode")))
      return
    }
    get(Endpoint.FILE_TARGETS, responseHandler = responseHandler)
  }

  fun putTaildropFiles(
      context: Context,
      peerId: StableNodeID,
      files: Collection<Ipn.OutgoingFile>,
      responseHandler: (Result<String>) -> Unit
  ) {
    // Taildrop not supported in VPNless mode
    responseHandler(Result.failure(Exception("Taildrop not yet supported in VPNless mode")))
  }

  private inline fun <reified T> get(
      path: String,
      body: ByteArray? = null,
      noinline responseHandler: (Result<T>) -> Unit
  ) {
    Request(
            scope = scope,
            method = "GET",
            path = path,
            body = body,
            responseType = typeOf<T>(),
            responseHandler = responseHandler)
        .execute()
  }

  private inline fun <reified T> put(
      path: String,
      body: ByteArray? = null,
      noinline responseHandler: (Result<T>) -> Unit
  ) {
    Request(
            scope = scope,
            method = "PUT",
            path = path,
            body = body,
            responseType = typeOf<T>(),
            responseHandler = responseHandler)
        .execute()
  }

  private inline fun <reified T> post(
      path: String,
      body: ByteArray? = null,
      timeoutMillis: Long = 30000,
      noinline responseHandler: (Result<T>) -> Unit
  ) {
    Request(
            scope = scope,
            method = "POST",
            path = path,
            body = body,
            timeoutMillis = timeoutMillis,
            responseType = typeOf<T>(),
            responseHandler = responseHandler)
        .execute()
  }

  private inline fun <reified T> patch(
      path: String,
      body: ByteArray? = null,
      noinline responseHandler: (Result<T>) -> Unit
  ) {
    Request(
            scope = scope,
            method = "PATCH",
            path = path,
            body = body,
            responseType = typeOf<T>(),
            responseHandler = responseHandler)
        .execute()
  }

  private inline fun <reified T> delete(
      path: String,
      noinline responseHandler: (Result<T>) -> Unit
  ) {
    Request(
            scope = scope,
            method = "DELETE",
            path = path,
            responseType = typeOf<T>(),
            responseHandler = responseHandler)
        .execute()
  }
}

/**
 * HTTP-based request to the tailscaled local API via Android LocalSocket (Unix domain socket).
 * This replaces the libtailscale.Application.callLocalAPI() mechanism.
 */
class Request<T>(
    private val scope: CoroutineScope,
    private val method: String,
    path: String,
    private val body: ByteArray? = null,
    private val timeoutMillis: Long = 30000,
    private val responseType: KType,
    private val responseHandler: (Result<T>) -> Unit
) {
  private val fullPath = "/localapi/v0/$path"

  companion object {
    private const val TAG = "LocalAPIRequest"
    private val jsonDecoder = Json { ignoreUnknownKeys = true }
    // Default socket path for local API calls (HTTP via Unix domain socket).
    // In VPNless mode, the RootLocalAPIClient (CLI-based) is used instead.
    private val socketPath = com.tailscale.ipn.RootTailscaleManager.APP_SOCK

    @JvmStatic
    fun setApp(newApp: Any?) {
      // No-op in VPNless mode
    }
  }

  @OptIn(ExperimentalSerializationApi::class)
  fun execute() {
    scope.launch(Dispatchers.IO) {
      TSLog.d(TAG, "Executing HTTP request: $method $fullPath via socket $socketPath")
      try {
        // Connect to the daemon's Unix socket using Android LocalSocket
        val localSocket = android.net.LocalSocket()
        localSocket.connect(android.net.LocalSocketAddress(
          socketPath,
          android.net.LocalSocketAddress.Namespace.FILESYSTEM
        ))
        localSocket.soTimeout = timeoutMillis.toInt()

        localSocket.use { sock ->
          val out = sock.getOutputStream()
          val input = sock.getInputStream()

          // Build the HTTP/1.1 request
          val requestBuilder = StringBuilder()
          requestBuilder.append("$method $fullPath HTTP/1.1\r\n")
          requestBuilder.append("Host: localtailscale\r\n")
          requestBuilder.append("Connection: close\r\n")
          if (body != null) {
            requestBuilder.append("Content-Length: ${body.size}\r\n")
          }
          requestBuilder.append("\r\n")

          out.write(requestBuilder.toString().toByteArray(Charsets.UTF_8))
          if (body != null) {
            out.write(body)
          }
          out.flush()

          // Read the full response
          val responseBytes = input.readBytes()
          val responseStr = responseBytes.toString(Charsets.UTF_8)
          val headerBodySplit = responseStr.split("\r\n\r\n", limit = 2)
          val headerLines = headerBodySplit[0].split("\r\n")
          val statusLine = headerLines.firstOrNull() ?: "HTTP/1.1 500"
          val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 500
          val responseBody = if (headerBodySplit.size > 1)
            headerBodySplit[1].toByteArray(Charsets.UTF_8) else ByteArray(0)

          @Suppress("UNCHECKED_CAST")
          val response: Result<T> =
              if (statusCode >= 400) {
                val errMsg = try {
                  jsonDecoder.decodeFromString<Errors.GenericError>(
                    responseBody.decodeToString()
                  ).error
                } catch (_: Exception) {
                  responseBody.decodeToString()
                }
                Result.failure(Exception("$statusCode: $errMsg"))
              } else {
                when (responseType) {
                  typeOf<String>() -> Result.success(responseBody.decodeToString() as T)
                  typeOf<Unit>() -> Result.success(Unit as T)
                  else -> try {
                    Result.success(
                      jsonDecoder.decodeFromString(
                        Json.serializersModule.serializer(responseType),
                        responseBody.decodeToString()
                      ) as T
                    )
                  } catch (t: Throwable) {
                    Result.success(responseBody.decodeToString() as T)
                  }
                }
              }
          scope.launch { responseHandler(response) }
        }
      } catch (e: Exception) {
        TSLog.e(TAG, "Error executing request:${method}:${fullPath}: $e")
        scope.launch { responseHandler(Result.failure(e)) }
      }
    }
  }
}
