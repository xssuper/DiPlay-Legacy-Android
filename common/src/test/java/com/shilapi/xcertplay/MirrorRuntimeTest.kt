package com.shilapi.xcertplay

import android.hardware.usb.UsbDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class MirrorRuntimeTest {

    private class RecordingActions : MirrorRuntime.Actions {
        var stopCarPlayCalls = 0
        var startAndroidCalls = 0
        var stopAndroidCalls = 0
        var startAndroidShouldSucceed = true

        override fun stopCarPlay() { stopCarPlayCalls++ }
        override fun startAndroid(device: UsbDevice): Boolean {
            startAndroidCalls++
            return startAndroidShouldSucceed
        }
        override fun stopAndroid() { stopAndroidCalls++ }
    }

    private fun device(id: Int): UsbDevice {
        val d = mock(UsbDevice::class.java)
        `when`(d.deviceId).thenReturn(id)
        return d
    }

    @Test fun androidAttachStartsReceiverAndStopsCarPlayWhenTakingOver() {
        val actions = RecordingActions()
        val runtime = MirrorRuntime(actions)
        runtime.onCarPlayStarted() // CarPlay already owns the screen

        val started = runtime.onAndroidPhoneAttached(device(12))

        assertTrue(started)
        assertEquals(1, actions.stopCarPlayCalls)
        assertEquals(1, actions.startAndroidCalls)
        assertTrue(runtime.androidActive)
    }

    @Test fun duplicateCableAttachIsIgnoredUntilDetach() {
        val actions = RecordingActions()
        val runtime = MirrorRuntime(actions)

        assertTrue(runtime.onAndroidPhoneAttached(device(12)))
        assertFalse(runtime.onAndroidPhoneAttached(device(12)))
        assertEquals(1, actions.startAndroidCalls)

        assertTrue(runtime.onUsbDetached(12))
        assertTrue(runtime.onAndroidPhoneAttached(device(12)))
        assertEquals(2, actions.startAndroidCalls)
    }

    @Test fun stopAndroidOnlyReleasesTheReceiver() {
        val actions = RecordingActions()
        val runtime = MirrorRuntime(actions)

        runtime.onAndroidPhoneAttached(device(12))
        runtime.stopAndroidOnly()

        assertEquals(1, actions.stopAndroidCalls)
        assertFalse(runtime.androidActive)
    }

    @Test fun releaseAllStopsBothModes() {
        val actions = RecordingActions()
        val runtime = MirrorRuntime(actions)
        runtime.onCarPlayStarted()

        runtime.releaseAll()

        assertEquals(1, actions.stopCarPlayCalls)
        assertEquals(0, actions.stopAndroidCalls)
    }

    @Test fun carPlayCannotClaimOwnershipWhileAndroidIsLive() {
        val actions = RecordingActions()
        val runtime = MirrorRuntime(actions)

        runtime.onAndroidPhoneAttached(device(12))
        // A CarPlay arrival is ignored by the arbiter while Android owns the screen.
        runtime.onCarPlayStarted()

        assertTrue(runtime.androidActive)
    }

    @Test fun failedAndroidStartReleasesTheReservation() {
        val actions = RecordingActions().apply { startAndroidShouldSucceed = false }
        val runtime = MirrorRuntime(actions)

        assertFalse(runtime.onAndroidPhoneAttached(device(7)))
        assertFalse(runtime.androidActive)
        assertEquals(1, actions.startAndroidCalls)
    }
}
