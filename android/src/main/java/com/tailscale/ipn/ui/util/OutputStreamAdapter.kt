// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import com.tailscale.ipn.util.TSLog
import java.io.OutputStream

/**
 * Adapter converting a Java OutputStream into a data-writing interface.
 * This is a plain Kotlin class (no libtailscale interface dependency)
 * used for writing Taildrop files to SAF content URIs.
 */
class OutputStreamAdapter(private val outputStream: OutputStream) {
  fun write(data: ByteArray): Long {
    return try {
      outputStream.write(data)
      outputStream.flush()
      data.size.toLong()
    } catch (e: Exception) {
      TSLog.d("OutputStreamAdapter", "write exception: $e")
      -1L
    }
  }

  fun close() {
    outputStream.close()
  }
}
