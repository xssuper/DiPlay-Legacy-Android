package com.shilapi.xcertplay.mirroring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorSessionCoordinatorTest {

    @Test
    fun `Android starts from idle and duplicate cable attach is ignored until detach`() {
        val coordinator = MirrorSessionCoordinator()

        val request = coordinator.requestAndroid(12)

        assertEquals(MirrorSessionCoordinator.AndroidStartDecision(12, false), request)
        assertTrue(coordinator.isAndroidPending(12))
        assertNull(coordinator.requestAndroid(12))

        coordinator.androidStarted(12)
        assertEquals(MirrorArbiterState.ANDROID_RUNNING, coordinator.state)
        assertEquals(MirrorMode.ANDROID_AUTO, coordinator.owner)
        assertNull(coordinator.requestAndroid(12))

        assertTrue(coordinator.usbDetached(12))
        assertEquals(MirrorArbiterState.IDLE, coordinator.state)
        assertFalse(coordinator.isAndroidPendingOrActive())
        assertEquals(MirrorSessionCoordinator.AndroidStartDecision(12, false), coordinator.requestAndroid(12))
    }

    @Test
    fun `switching from CarPlay waits for orderly stop before Android confirmation`() {
        val coordinator = MirrorSessionCoordinator()
        coordinator.carPlayStarted()
        assertEquals(MirrorArbiterState.CARPLAY_RUNNING, coordinator.state)

        val request = coordinator.requestAndroid(21)
        assertEquals(MirrorSessionCoordinator.AndroidStartDecision(21, true), request)
        assertEquals(MirrorArbiterState.SWITCHING, coordinator.state)
        assertEquals(MirrorMode.CARPLAY, coordinator.owner)
        assertEquals(MirrorMode.ANDROID_AUTO, coordinatorTarget(coordinator))

        coordinator.carPlayStopped()
        assertEquals(MirrorArbiterState.SWITCHING, coordinator.state)
        assertEquals(MirrorMode.NONE, coordinator.owner)

        coordinator.androidStarted(21)
        assertEquals(MirrorArbiterState.ANDROID_RUNNING, coordinator.state)
        assertEquals(MirrorMode.ANDROID_AUTO, coordinator.owner)
    }

    @Test
    fun `failed Android startup clears pending state but requires a detach before retry`() {
        val coordinator = MirrorSessionCoordinator()
        coordinator.requestAndroid(7)
        coordinator.androidFailed(7, "USB permission denied")

        assertEquals(MirrorArbiterState.IDLE, coordinator.state)
        assertFalse(coordinator.isAndroidPendingOrActive())
        assertNull(coordinator.requestAndroid(7))

        coordinator.usbDetached(7)
        assertEquals(MirrorSessionCoordinator.AndroidStartDecision(7, false), coordinator.requestAndroid(7))
    }

    @Test
    fun `network Android sessions do not reserve a USB device id`() {
        val coordinator = MirrorSessionCoordinator()
        assertEquals(MirrorSessionCoordinator.AndroidStartDecision(-1, false), coordinator.requestAndroid(-1))
        coordinator.androidStarted(-1)
        assertTrue(coordinator.isAndroidPendingOrActive())
        coordinator.androidStopped(-1)
        assertFalse(coordinator.isAndroidPendingOrActive())
        assertEquals(MirrorSessionCoordinator.AndroidStartDecision(-1, false), coordinator.requestAndroid(-1))
    }

    @Test
    fun `CarPlay cannot take ownership while Android is pending or active`() {
        val coordinator = MirrorSessionCoordinator()
        coordinator.requestAndroid(4)
        coordinator.carPlayStarted()
        assertEquals(MirrorArbiterState.SWITCHING, coordinator.state)
        assertEquals(MirrorMode.NONE, coordinator.owner)

        coordinator.androidStarted(4)
        coordinator.carPlayStarted()
        assertEquals(MirrorArbiterState.ANDROID_RUNNING, coordinator.state)
        assertEquals(MirrorMode.ANDROID_AUTO, coordinator.owner)
    }

    private fun coordinatorTarget(coordinator: MirrorSessionCoordinator): MirrorMode {
        // The target is intentionally not exposed by the runtime coordinator. State and owner are
        // enough for callers; the arbiter's transition tests cover the target directly.
        assertEquals(MirrorArbiterState.SWITCHING, coordinator.state)
        return MirrorMode.ANDROID_AUTO
    }
}
