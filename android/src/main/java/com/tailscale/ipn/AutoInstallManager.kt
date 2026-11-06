// VPNless - Automatic download & install of tailscale/tailscaled binaries
// Downloads the official Tailscale static build for arm64, extracts, and
// installs to the app's data directory. Manages tailscaled daemon lifecycle.
package com.tailscale.ipn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class AutoInstallManager(private val context: Context) {
  companion object {
    private const val TAG = "AutoInstall"
    private const val TS_RELEASE_URL = "https://pkgs.tailscale.com/stable/tailscale_1.80.0_arm64.tgz"
    private const val TS_BINARY_NAME = "tailscale"
    private const val TSD_BINARY_NAME = "tailscaled"

    private var _installDir: String = ""
    private var appFilesDir: String = ""

    private fun installDir() = "$appFilesDir/tailscale"
    private fun installTailscalePath() = "${installDir()}/$TS_BINARY_NAME"
    private fun installTailscaledPath() = "${installDir()}/$TSD_BINARY_NAME"
    private fun sockPath() = "${installDir()}/tailscaled.sock"
    private fun varDir() = "${installDir()}/var"
  }

  fun init(filesDir: String) {
    appFilesDir = filesDir
    _installDir = installDir()
  }

  fun getInstallDir() = installDir()
  fun getTailscalePath() = installTailscalePath()
  fun getSocketPath() = sockPath()

  /** Check if the app has root access */
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

  suspend fun isInstalled(): Boolean = withContext(Dispatchers.IO) {
    File(installTailscalePath()).exists() && File(installTailscalePath()).canExecute() &&
      File(installTailscaledPath()).exists() && File(installTailscaledPath()).canExecute()
  }

  suspend fun install(onProgress: (String) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
    try {
      onProgress("Creating directories...")
      File(installDir()).mkdirs()
      File(varDir()).mkdirs()

      onProgress("Downloading tailscale binaries (arm64)...")
      val tgzFile = File(installDir(), "tailscale.tgz")
      downloadFile(TS_RELEASE_URL, tgzFile)

      onProgress("Extracting...")
      extractTgz(tgzFile, installDir())

      onProgress("Setting permissions...")
      runRootShell("chmod 755 ${installTailscalePath()} ${installTailscaledPath()}")

      tgzFile.delete()
      val ok = isInstalled()
      onProgress(if (ok) "Tailscale installed successfully" else "Installation verification failed")
      ok
    } catch (e: Exception) {
      Log.e(TAG, "Install failed: $e")
      onProgress("Install failed: ${e.message}")
      false
    }
  }

  suspend fun startDaemon(): Boolean = withContext(Dispatchers.IO) {
    try {
      val running = runRootShell("pgrep -f tailscaled")
      if (running.isNotEmpty()) {
        Log.i(TAG, "tailscaled already running")
        return@withContext true
      }

      val logFile = "${varDir()}/tailscaled.log"
      val cmd =
        "nohup ${installTailscaledPath()} --socket ${sockPath()}" +
          " --state ${varDir()}/tailscaled.state --tun userspace-networking" +
          " --no-logs-no-support > $logFile 2>&1 &"
      runRootShell(cmd)
      kotlinx.coroutines.delay(2000)

      val verify = runRootShell("pgrep -f tailscaled")
      val started = verify.isNotEmpty()
      Log.i(TAG, if (started) "tailscaled started" else "tailscaled failed to start")
      started
    } catch (e: Exception) {
      Log.e(TAG, "startDaemon error: $e")
      false
    }
  }

  suspend fun stopDaemon(): Boolean = withContext(Dispatchers.IO) {
    try {
      runRootShell("pkill -f tailscaled 2>/dev/null || true")
      kotlinx.coroutines.delay(500)
      true
    } catch (e: Exception) {
      Log.e(TAG, "stopDaemon error: $e")
      false
    }
  }

  private suspend fun runRootShell(command: String): String = withContext(Dispatchers.IO) {
    var proc: Process? = null
    try {
      proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
      // Close stdin so su doesn't wait for input
      proc.outputStream.close()
      // Read output with a timeout to prevent hangs on background processes
      val output = readStreamWithTimeout(proc.inputStream, 5000)
      val err = readStreamWithTimeout(proc.errorStream, 5000)
      if (!proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
        Log.w(TAG, "runRootShell timed out for: ${command.take(80)}")
        proc.destroyForcibly()
      }
      (output + err).trim()
    } catch (e: Exception) {
      Log.e(TAG, "runRootShell error: $e")
      proc?.destroyForcibly()
      ""
    }
  }

  /** Read an InputStream with a timeout in milliseconds.
   *  Returns the read content (possibly empty). */
  private fun readStreamWithTimeout(stream: java.io.InputStream, timeoutMs: Long): String = try {
    val reader = java.io.BufferedReader(java.io.InputStreamReader(stream))
    val sb = StringBuilder()
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < timeoutMs) {
      if (reader.ready()) {
        val c = reader.read()
        if (c == -1) break
        sb.append(c.toChar())
      } else {
        Thread.sleep(10)
      }
    }
    sb.toString()
  } catch (e: java.io.IOException) {
    ""
  }

  private fun downloadFile(url: String, output: File) {
    val connection = URL(url).openConnection()
    connection.connectTimeout = 30000
    connection.readTimeout = 120000
    val inputStream = connection.getInputStream()
    FileOutputStream(output).use { fos ->
      inputStream.copyTo(fos, bufferSize = 8192)
    }
    inputStream.close()
  }

  private suspend fun extractTgz(tgzFile: File, outputDir: String) {
    val result = runRootShell("tar -xzf ${tgzFile.absolutePath} -C $outputDir --strip-components=1")
    if (result.contains("tar:")) {
      throw Exception("tar extraction failed: $result")
    }
    Log.i(TAG, "Extracted to $outputDir")
  }
}
