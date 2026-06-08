// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import java.io.InputStream

/**
 * Adapter converting a Java InputStream into a chunked read interface.
 * This is a plain Kotlin class (no libtailscale interface dependency)
 * used for reading data from content URIs and file streams.
 */
class InputStreamAdapter(private val inputStream: InputStream) {
  fun read(): ByteArray? {
    val b = ByteArray(4096)
    val i = inputStream.read(b)
    if (i == -1) {
      return null
    }
    return b.sliceArray(0 ..< i)
  }

  fun close() {
    inputStream.close()
  }
}
