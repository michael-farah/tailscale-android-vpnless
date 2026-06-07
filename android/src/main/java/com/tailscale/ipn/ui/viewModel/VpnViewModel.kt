// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class VpnViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(VpnViewModel::class.java)) {
      return VpnViewModel(application) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class")
  }
}

// Application context aware view model that tracks VPN state.
// In VPNless mode this is simplified since no VpnService.prepare is needed.
class VpnViewModel(application: Application) : AndroidViewModel(application) {
  val _vpnPrepared = MutableStateFlow(true)
  val vpnPrepared: StateFlow<Boolean> = _vpnPrepared
  val _vpnActive = MutableStateFlow(false)
  val vpnActive: StateFlow<Boolean> = _vpnActive
  val TAG = "VpnViewModel"

  init {
    setVpnPrepared(true)
  }

  fun setVpnActive(isActive: Boolean) {
    _vpnActive.value = isActive
  }

  fun setVpnPrepared(isPrepared: Boolean) {
    _vpnPrepared.value = isPrepared
  }
}
