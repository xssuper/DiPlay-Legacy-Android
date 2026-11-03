package com.shilapi.xcertplay.mirroring

/**
 * Process-local coordination for the single projection owner.
 *
 * The Android-facing runtime performs the effects (stop the retained CarPlay controller, then launch
 * the ADB mirror Activity). This class owns the decision state and duplicate-attach guard so the
 * transition is deterministic and testable without Android framework classes.
 */
class MirrorSessionCoordinator {
    data class AndroidStartDecision(
        val deviceId: Int,
        /** True when CarPlay (or an in-flight switch) must release resources before Android starts. */
        val stopCarPlay: Boolean,
    )

    private val arbiter = MirrorArbiter()
    private var pendingAndroidDeviceId: Int? = null
    private var activeAndroidDeviceId: Int? = null
    private var lastHandledUsbDeviceId: Int? = null

    val state: MirrorArbiterState get() = arbiter.state
    val owner: MirrorMode get() = arbiter.owner

    @Synchronized
    fun requestAndroid(deviceId: Int): AndroidStartDecision? {
        if (pendingAndroidDeviceId != null || activeAndroidDeviceId != null) return null
        if (deviceId >= 0 && lastHandledUsbDeviceId == deviceId) return null

        val effects = arbiter.onEvent(MirrorArbiterEvent.PhoneAttached(MirrorMode.ANDROID_AUTO))
        if (MirrorArbiterEffect.StartMode(MirrorMode.ANDROID_AUTO) !in effects) return null

        pendingAndroidDeviceId = deviceId
        if (deviceId >= 0) lastHandledUsbDeviceId = deviceId
        return AndroidStartDecision(
            deviceId = deviceId,
            stopCarPlay = MirrorArbiterEffect.StopMode(MirrorMode.CARPLAY) in effects ||
                MirrorArbiterEffect.ReleaseAll in effects,
        )
    }

    @Synchronized
    fun androidStarted(deviceId: Int) {
        if (pendingAndroidDeviceId != deviceId) return
        arbiter.onEvent(MirrorArbiterEvent.ModeStarted(MirrorMode.ANDROID_AUTO))
        pendingAndroidDeviceId = null
        activeAndroidDeviceId = deviceId
    }

    @Synchronized
    fun androidFailed(deviceId: Int, reason: String) {
        if (pendingAndroidDeviceId != deviceId) return
        arbiter.onEvent(MirrorArbiterEvent.ModeFailed(MirrorMode.ANDROID_AUTO, reason))
        pendingAndroidDeviceId = null
    }

    @Synchronized
    fun androidStopped(deviceId: Int) {
        if (pendingAndroidDeviceId == deviceId) {
            arbiter.onEvent(MirrorArbiterEvent.ModeStopped(MirrorMode.ANDROID_AUTO))
            pendingAndroidDeviceId = null
        }
        if (activeAndroidDeviceId == deviceId) {
            arbiter.onEvent(MirrorArbiterEvent.ModeStopped(MirrorMode.ANDROID_AUTO))
            activeAndroidDeviceId = null
        }
    }

    /**
     * Drops any Android ownership (cable or network) and acknowledges the stop, so a later CarPlay
     * attach is not blocked by stale ownership. Returns true when Android actually held the screen.
     */
    @Synchronized
    fun forceAndroidStop(): Boolean {
        val had = pendingAndroidDeviceId != null || activeAndroidDeviceId != null
        pendingAndroidDeviceId?.let { androidStopped(it) }
        activeAndroidDeviceId?.let { androidStopped(it) }
        return had
    }

    /** Clears the cable guard and releases Android ownership when the active phone is unplugged. */
    @Synchronized
    fun usbDetached(deviceId: Int): Boolean {
        val wasActive = pendingAndroidDeviceId == deviceId || activeAndroidDeviceId == deviceId
        if (wasActive) androidStopped(deviceId)
        if (lastHandledUsbDeviceId == deviceId) lastHandledUsbDeviceId = null
        return wasActive
    }

    @Synchronized
    fun carPlayStarted() {
        if (pendingAndroidDeviceId != null || activeAndroidDeviceId != null) return
        val effects = arbiter.onEvent(MirrorArbiterEvent.PhoneAttached(MirrorMode.CARPLAY))
        if (MirrorArbiterEffect.StartMode(MirrorMode.CARPLAY) in effects) {
            arbiter.onEvent(MirrorArbiterEvent.ModeStarted(MirrorMode.CARPLAY))
        }
    }

    /** Orderly CarPlay teardown preserves an Android target that is waiting for the stop callback. */
    @Synchronized
    fun carPlayStopped() {
        arbiter.onEvent(MirrorArbiterEvent.ModeStopped(MirrorMode.CARPLAY))
    }

    @Synchronized
    fun isAndroidPending(deviceId: Int): Boolean = pendingAndroidDeviceId == deviceId

    @Synchronized
    fun isAndroidPendingOrActive(): Boolean =
        pendingAndroidDeviceId != null || activeAndroidDeviceId != null
}
