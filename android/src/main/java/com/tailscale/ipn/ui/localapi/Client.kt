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
import com.tailscale.ipn.ui.util.InputStreamAdapter
import com.tailscale.ipn.util.TSLog
import java.nio.charset.Charset
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.serializer
import libtailscale.FilePart

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
  }

  private val TAG = Client::class.simpleName

  // Access libtailscale.Application lazily (only when not using root daemon)
  private val app: libtailscale.Application? by lazy {
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
    if (useRootDaemon) {
      responseHandler(Result.failure(Exception("Taildrop not yet supported in VPNless mode")))
      return
    }

    val manifest = Json.encodeToString(files)
    val manifestPart = FilePart()
    manifestPart.body = InputStreamAdapter(manifest.byteInputStream(Charset.defaultCharset()))
    manifestPart.filename = "manifest.json"
    manifestPart.contentType = "application/json"
    val parts = mutableListOf(manifestPart)

    try {
      parts.addAll(
          files.map { file ->
            val stream =
                context.contentResolver.openInputStream(file.uri)
                    ?: throw Exception("Error opening file stream")

            val part = FilePart()
            part.filename = file.Name
            part.contentLength = file.DeclaredSize
            part.body = InputStreamAdapter(stream)
            part
          })
    } catch (e: Exception) {
      parts.forEach { it.body.close() }
      TSLog.e(TAG, "Error creating file upload body: $e")
      responseHandler(Result.failure(e))
      return
    }

    return postMultipart(
        "${Endpoint.FILE_PUT}/${peerId}",
        FileParts(parts),
        responseHandler,
    )
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

  private inline fun <reified T> postMultipart(
      path: String,
      parts: FileParts,
      noinline responseHandler: (Result<T>) -> Unit
  ) {
    Request(
            scope = scope,
            method = "POST",
            path = path,
            parts = parts,
            timeoutMillis = 24 * 60 * 60 * 1000, // 24 hours
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

class Request<T>(
    private val scope: CoroutineScope,
    private val method: String,
    path: String,
    private val body: ByteArray? = null,
    private val parts: FileParts? = null,
    private val timeoutMillis: Long = 30000,
    private val responseType: KType,
    private val responseHandler: (Result<T>) -> Unit
) {
  private val fullPath = "/localapi/v0/$path"

  companion object {
    private const val TAG = "LocalAPIRequest"

    private val jsonDecoder = Json { ignoreUnknownKeys = true }

    private lateinit var app: libtailscale.Application

    @JvmStatic
    fun setApp(newApp: libtailscale.Application) {
      app = newApp
    }
  }

  @OptIn(ExperimentalSerializationApi::class)
  fun execute() {
    scope.launch(Dispatchers.IO) {
      TSLog.d(TAG, "Executing request:${method}:${fullPath} on app $app")
      try {
        val resp =
            if (parts != null) app.callLocalAPIMultipart(timeoutMillis, method, fullPath, parts)
            else
                app.callLocalAPI(
                    timeoutMillis,
                    method,
                    fullPath,
                    body?.let { InputStreamAdapter(it.inputStream()) })
        val respData = resp.bodyBytes() ?: ByteArray(0)

        @Suppress("UNCHECKED_CAST")
        val response: Result<T> =
            when (responseType) {
              typeOf<String>() -> Result.success(respData.decodeToString() as T)
              typeOf<Unit>() -> Result.success(Unit as T)
              else ->
                  try {
                    Result.success(
                        jsonDecoder.decodeFromStream(
                            Json.serializersModule.serializer(responseType), respData.inputStream())
                            as T)
                  } catch (t: Throwable) {
                    // If we couldn't parse the response body, assume it's an error response
                    try {
                      val error =
                          jsonDecoder.decodeFromStream<Errors.GenericError>(respData.inputStream())
                      throw Exception(error.error)
                    } catch (t: Throwable) {
                      Result.failure(t)
                    }
                  }
            }
        if (resp.statusCode() >= 400) {
          throw Exception(
              "Request failed with status ${resp.statusCode()}: ${respData.toString(Charset.defaultCharset())}")
        }
        scope.launch { responseHandler(response) }
      } catch (e: Exception) {
        TSLog.e(TAG, "Error executing request:${method}:${fullPath}: $e")
        scope.launch { responseHandler(Result.failure(e)) }
      }
    }
  }
}

class FileParts(private val parts: List<FilePart>) : libtailscale.FileParts {
  override fun get(i: Int): FilePart {
    return parts[i]
  }

  override fun len(): Int {
    return parts.size
  }
}
