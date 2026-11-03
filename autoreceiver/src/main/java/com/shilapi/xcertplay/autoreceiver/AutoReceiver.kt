package com.shilapi.xcertplay.autoreceiver

import android.content.Context
import android.hardware.usb.UsbDevice

/**
 * The narrow seam between DiPlay and the merged DiAuto receiver (plan B).
 *
 * DiPlay only needs four things from the receiver: start it, stop it, ask what it is doing, and
 * hear about changes. Everything else — the protocol, decoders, USB accessory handling, wireless
 * discovery — stays inside `com.andrerinas.openheadunit` and is free to change with upstream.
 */
interface AutoReceiver {

    /** What the receiver is doing right now. */
    enum class State {
        /** Nothing running; no phone is being served. */
        IDLE,

        /** A session is being negotiated (USB accessory switch, TLS handshake, wireless discovery). */
        STARTING,

        /** A phone is connected and the projection surface is live. */
        PROJECTING,

        /** The last attempt failed; DiPlay should show the reason and offer a retry. */
        ERROR,
    }

    /** One listener per surface that wants to follow the session; called on the main thread. */
    fun interface Listener {
        fun onStateChanged(state: State, detail: String?)

        companion object {
            /** Kotlin callers that only care about the state can ignore the detail. */
            fun of(block: (State) -> Unit): Listener = Listener { state, _ -> block(state) }
        }
    }

    /**
     * Starts the receiver for [device], or for a wireless phone when [device] is null.
     *
     * Returns false when a session is already starting or running, so a repeated USB attach cannot
     * stack two sessions on top of each other.
     */
    fun start(context: Context, device: UsbDevice? = null): Boolean

    /** Stops the session and releases the surface. Safe to call when nothing is running. */
    fun stop(context: Context)

    /** Current state, readable without starting anything. */
    fun state(context: Context): State

    fun addListener(listener: Listener)

    fun removeListener(listener: Listener)
}
