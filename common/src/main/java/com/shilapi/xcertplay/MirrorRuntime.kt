package com.shilapi.xcertplay

import android.hardware.usb.UsbDevice
import com.shilapi.xcertplay.autoreceiver.DiAutoReceiver
import com.shilapi.xcertplay.mirroring.MirrorSessionCoordinator

/**
 * Runtime half of the M4 single-owner rule.
 *
 * [MirrorSessionCoordinator] decides who may own the screen, the USB device, the hotspot and the
 * local ADB link; this class is the one place that turns those decisions into work on the head
 * unit. Keeping the decision and the work apart is what lets the arbiter be covered by pure JVM
 * tests while the effects here stay thin adapters over the existing CarPlay and receiver calls.
 *
 * Exactly one projection mode may be live. When an Android phone is served, the CarPlay controller
 * is torn down first; when CarPlay is served, any running Android receiver is released first. The
 * [Actions] interface is the only seam the host activity has to fill in.
 */
class MirrorRuntime(private val actions: Actions) {

    private val coordinator = MirrorSessionCoordinator()

    /** True while an Android session (cable or network) holds the screen. */
    val androidActive: Boolean get() = coordinator.isAndroidPendingOrActive()

    /**
     * A cable Android phone attached. Returns true when the receiver was asked to start. When
     * CarPlay owns the screen the receiver is started only after [Actions.stopCarPlay] yields it,
     * exactly as [MirrorSessionCoordinator] prescribes.
     */
    fun onAndroidPhoneAttached(device: UsbDevice): Boolean {
        val decision = coordinator.requestAndroid(device.deviceId) ?: return false
        if (decision.stopCarPlay) actions.stopCarPlay()
        val started = actions.startAndroid(device)
        if (started) coordinator.androidStarted(device.deviceId)
        else coordinator.androidFailed(device.deviceId, "receiver refused to start")
        return started
    }

    /**
     * Releases whatever Android session owns the screen without touching CarPlay. Used when an
     * iPhone is plugged in while Android was live, and when Android mode is switched off.
     */
    fun stopAndroidOnly() {
        if (coordinator.forceAndroidStop()) actions.stopAndroid()
    }

    /** CarPlay is actually live (fresh start or restart). */
    fun onCarPlayStarted() {
        coordinator.carPlayStarted()
    }

    /** CarPlay stopped on its own (driver left, phone unplugged). */
    fun onCarPlayStopped() {
        coordinator.carPlayStopped()
    }

    /** A cable phone was unplugged; release any Android ownership it held. */
    fun onUsbDetached(deviceId: Int): Boolean {
        val wasActive = coordinator.usbDetached(deviceId)
        if (wasActive) actions.stopAndroid()
        return wasActive
    }

    /** Force everything down — used when Android mode is switched off mid-session. */
    fun releaseAll() {
        if (coordinator.forceAndroidStop()) actions.stopAndroid()
        actions.stopCarPlay()
    }

    interface Actions {
        /** Tear the CarPlay stack down without scheduling a restart. */
        fun stopCarPlay()

        /** Start the merged Android Auto receiver for [device]. Returns false when it refused. */
        fun startAndroid(device: UsbDevice): Boolean

        /** Stop the merged Android Auto receiver. Safe no-op when nothing is running. */
        fun stopAndroid()
    }
}
