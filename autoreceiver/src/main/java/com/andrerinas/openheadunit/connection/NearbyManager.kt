package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.utils.AppLog
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stand-in for DiAuto's Nearby Connections discovery.
 *
 * The upstream implementation depends on `com.google.android.gms:play-services-nearby`, which
 * declares `minSdk 21`. DiPlay still ships to Android 4.4 (API 17), and forcing that AAR into a
 * 17-minSdk app risks crashing legacy head units on a Play-services entry point that no driver
 * asked for.
 *
 * This class keeps the exact API the receiver calls and simply reports that Nearby is not part of
 * this build. Wireless discovery therefore falls back to the strategies the receiver already has —
 * Wi-Fi Direct, the Bluetooth path and plain network discovery — all of which are independent of
 * Play services.
 *
 * To bring Nearby back: add `com.google.android.gms:play-services-nearby` to
 * `autoreceiver/build.gradle.kts`, restore the upstream file from DiAuto at the pinned commit in
 * `docs/AA_MERGE_M0.md`, and raise the module's minSdk to 21 for a Play-services-only variant.
 */
class NearbyManager(
    private val context: android.content.Context,
    private val scope: CoroutineScope,
    private val onSocketConnected: (Socket) -> Unit,
) {
    data class DiscoveredEndpoint(val id: String, val name: String)

    companion object {
        /** False in this build; see the class comment. */
        const val isAvailable = false

        private val _discoveredEndpoints = MutableStateFlow<List<DiscoveredEndpoint>>(emptyList())
        val discoveredEndpoints: StateFlow<List<DiscoveredEndpoint>> = _discoveredEndpoints
    }

    fun start() {
        AppLog.i("NearbyManager: not part of this build (minSdk 17); using the other discovery paths")
    }

    fun stop() {
        _discoveredEndpoints.value = emptyList()
    }

    fun connectToEndpoint(endpointId: String) {
        AppLog.w("NearbyManager: endpoint $endpointId ignored, Nearby is not part of this build")
    }
}
