// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.util

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.tailscale.ipn.TaildropDirectoryStore
import com.tailscale.ipn.ui.util.InputStreamAdapter
import com.tailscale.ipn.ui.util.OutputStreamAdapter
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

data class SafFile(val fd: Int, val uri: String)

object ShareFileHelper {
  private var appContext: Context? = null
  private var savedUri: String? = null
  private var scope: CoroutineScope? = null

  @JvmStatic
  fun init(context: Context, uri: String, appScope: CoroutineScope) {
    appContext = context.applicationContext
    savedUri = uri
    scope = appScope
    TSLog.d("ShareFileHelper", "init ShareFileHelper with savedUri: $savedUri")
  }

  // A simple data class that holds a SAF OutputStream along with its URI.
  data class SafStream(val uri: String, val stream: OutputStream)

  val taildropPrompt = MutableSharedFlow<Unit>(replay = 1)

  fun observeTaildropPrompt(): Flow<Unit> = taildropPrompt

  @Volatile private var directoryReady: CompletableDeferred<Unit>? = null

  fun hasValidTaildropDir(): Boolean {
    val uri = TaildropDirectoryStore.loadSavedDir()
    if (uri == null) return false

    // Only SAF tree URIs are supported
    if (uri.scheme != "content") {
      TSLog.w("ShareFileHelper", "Invalid URI scheme for taildrop dir: ${uri.scheme}")
      return false
    }

    val context = appContext ?: return false
    val docFile = DocumentFile.fromTreeUri(context, uri)

    if (docFile == null || !docFile.exists() || !docFile.canWrite()) {
      TSLog.w("ShareFileHelper", "Stored taildrop URI is invalid or inaccessible: $uri")
      return false
    }

    return true
  }

  fun notifyDirectoryReady() {
    directoryReady?.takeIf { !it.isCompleted }?.complete(Unit)
  }

  fun setUri(uri: String) {
    savedUri = uri
  }

  private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<Uri?>>()

  private fun pendingSafUri(tag: String): Uri? {
    try {
      val uri = if (tag.startsWith("/")) {
        Uri.fromFile(java.io.File(tag))
      } else {
        val parts = tag.split("|")
        Uri.parse(parts[0])
      }
      val fd = appContext!!.contentResolver.openFileDescriptor(uri, "r") ?: return null
      return Uri.fromParts("saf", fd.fd.toString(), null).also {
        concurrentFDs[fd.fd] = SafFile(fd.fd, tag)
      }
    } catch (e: Exception) {
      TSLog.e("ShareFileHelper", "pendingSafUri error: $e")
      return null
    }
  }

  private val concurrentFDs = ConcurrentHashMap<Int, SafFile>()

  fun openFileWriter(fileName: String, offset: Long): OutputStreamAdapter? {
    try {
      val safUri = pendingSafUri(fileName)
      if (safUri != null && appContext != null) {
        val pfd =
            appContext!!.contentResolver.openFileDescriptor(safUri, "rwt")
                ?: return null
        // Offset not used with rwt mode (file is truncated)
        // In the original tailscale app, this would seek for partial writes
        val stream = FileOutputStream(pfd.fileDescriptor)
        return OutputStreamAdapter(stream)
      }
    } catch (e: Exception) {
      TSLog.e("ShareFileHelper", "openFileWriter failed: $e")
    }
    if (savedUri == null) return null
    val baseUri = Uri.parse(savedUri)
    val documentFile = DocumentFile.fromTreeUri(appContext!!, baseUri) ?: return null
    val newFile =
        documentFile.createFile("*/*", fileName)
            ?: run {
              TSLog.e("ShareFileHelper", "openFileWriter: failed to create file $fileName")
              return null
            }
    val stream = appContext!!.contentResolver.openOutputStream(newFile.uri, "rwt")
    if (stream == null) return null
    return OutputStreamAdapter(stream)
  }

  fun getFileURI(fileName: String): String {
    val safUri = pendingSafUri(fileName)
    if (safUri != null) return safUri.toString()
    if (savedUri == null) return ""
    val baseUri = Uri.parse(savedUri)
    val documentFile = DocumentFile.fromTreeUri(appContext!!, baseUri) ?: return ""
    // Retrieve the existing document via the SAF, falling back to creating a new file entry.
    val existingDoc = documentFile.findFile(fileName)
    val fileDoc = existingDoc ?: documentFile.createFile("*/*", fileName) ?: return ""
    return fileDoc.uri.toString()
  }

  fun renameFile(oldPath: String, targetName: String): String {
    val safUri = pendingSafUri(oldPath)
    if (safUri != null) {
      // This is a local file descriptor reference, not a document file. Rename is not possible.
      TSLog.w("ShareFileHelper", "renameFile: cannot rename a pending SAF URI")
      return oldPath
    }
    if (appContext != null) {
      val uri = Uri.parse(oldPath)
      val docFile = DocumentFile.fromSingleUri(appContext!!, uri)
      if (docFile != null && docFile.renameTo(targetName)) {
        return docFile.uri.toString()
      }
    }
    // Fall back: return target URI under the saved folder
    if (savedUri == null) return ""
    return "$savedUri/$targetName"
  }

  fun listFilesJSON(suffix: String): String {
    val thisSavedUri = savedUri ?: return "[]"
    if (appContext == null) return "[]"
    val baseUri = Uri.parse(thisSavedUri)
    val documentFile = DocumentFile.fromTreeUri(appContext!!, baseUri) ?: return "[]"
    val files = documentFile.listFiles()
    val json = JSONObject()
    for (file in files) {
      val name = file.name ?: continue
      if (suffix.isNotEmpty() && !name.endsWith(suffix)) continue
      val fileJson = JSONObject()
      fileJson.put("name", name)
      fileJson.put("size", file.length())
      fileJson.put("modTime", file.lastModified())
      json.put(name, fileJson)
    }
    return json.toString()
  }

  fun openFileReader(name: String): InputStreamAdapter? {
    val thisSavedUri = savedUri ?: return null
    if (appContext == null) return null
    val baseUri = Uri.parse(thisSavedUri)
    val documentFile = DocumentFile.fromTreeUri(appContext!!, baseUri) ?: return null
    val file = documentFile.findFile(name) ?: return null
    val inStream = appContext!!.contentResolver.openInputStream(file.uri) ?: return null
    return InputStreamAdapter(inStream)
  }

  fun deleteFile(uri: String): Boolean {
    if (appContext == null) return false
    val docsUri = Uri.parse(uri)
    return DocumentsContract.deleteDocument(appContext!!.contentResolver, docsUri)
  }

  fun getFileInfo(fileName: String): String {
    val thisSavedUri = savedUri ?: return "{}"
    if (appContext == null) return "{}"
    val baseUri = Uri.parse(thisSavedUri)
    val documentFile = DocumentFile.fromTreeUri(appContext!!, baseUri) ?: return "{}"
    val file = documentFile.findFile(fileName) ?: return "{}"
    val json = JSONObject()
    json.put("name", file.name)
    json.put("size", file.length())
    json.put("modTime", file.lastModified())
    return json.toString()
  }

  private class SeekableOutputStream(
      private val fos: FileOutputStream,
      private val pfd: ParcelFileDescriptor
  ) : OutputStream() {
    private var closed = false

    override fun write(b: Int) = fos.write(b)

    override fun write(b: ByteArray) = fos.write(b)

    override fun write(b: ByteArray, off: Int, len: Int) {
      fos.write(b, off, len)
    }

    override fun close() {
      if (!closed) {
        closed = true
        try {
          fos.close()
        } finally {
          pfd.close()
        }
      }
    }
  }
}
