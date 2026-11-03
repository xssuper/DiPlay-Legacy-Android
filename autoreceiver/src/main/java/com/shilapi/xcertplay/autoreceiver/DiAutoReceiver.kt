package com.shilapi.xcertplay.autoreceiver

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.app.UsbAttachedActivity
import com.andrerinas.openheadunit.connection.CommManager
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * [AutoReceiver] on top of the merged DiAuto receiver.
 *
 * The receiver keeps its own service and connection state machine, so DiPlay never touches the
 * protocol. This object only translates the upstream state into the four values DiPlay cares
 * about and forwards start/stop intents, which keeps the seam in [AutoReceiver] honest.
 */
object DiAutoReceiver : AutoReceiver {
    private const val TAG = "DiAutoReceiver"

    private val listeners = CopyOnWriteArrayList<AutoReceiver.Listener>()
    private var scope: CoroutineScope? = null
    private var stateJob: Job? = null

    @Volatile
    private var lastState: AutoReceiver.State = AutoReceiver.State.IDLE

    @Volatile
    private var lastDetail: String? = null

    override fun start(context: Context, device: UsbDevice?): Boolean {
        val component = runCatching { App.provide(context) }.getOrElse {
            Log.w(TAG, "Receiver runtime unavailable (Android mode is off?)", it)
            return false
        }

        val current = component.commManager.connectionState.value
        if (current is CommManager.ConnectionState.Connecting ||
            current is CommManager.ConnectionState.StartingTransport ||
            current is CommManager.ConnectionState.HandshakeComplete ||
            current is CommManager.ConnectionState.Connected ||
            current is CommManager.ConnectionState.TransportStarted
        ) {
            Log.i(TAG, "Ignoring start request, session already $current")
            return false
        }

        observe(context)

        if (device != null) {
            // The upstream path owns the whole wired dance: request the USB permission, switch the
            // phone into accessory mode, then hand the accessory to AapService.
            val intent = Intent(context, UsbAttachedActivity::class.java)
                .putExtra(UsbManager.EXTRA_DEVICE, device)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .onFailure { Log.w(TAG, "Could not start USB attach flow", it); return false }
            return true
        }

        // Wireless: the service scans, pairs and connects on its own.
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AapService::class.java).setAction(AapService.ACTION_START_WIRELESS),
            )
        }.onFailure {
            Log.w(TAG, "Could not start wireless session", it)
            return false
        }
        return true
    }

    override fun stop(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AapService::class.java).setAction(AapService.ACTION_STOP_SERVICE),
            )
        }.onFailure { Log.w(TAG, "Could not stop the receiver", it) }
    }

    override fun state(context: Context): AutoReceiver.State = runCatching {
        map(App.provide(context).commManager.connectionState.value)
    }.getOrDefault(lastState)

    override fun addListener(listener: AutoReceiver.Listener) {
        listeners += listener
        listener.onStateChanged(lastState, lastDetail)
    }

    override fun removeListener(listener: AutoReceiver.Listener) {
        listeners -= listener
        if (listeners.isEmpty()) {
            stateJob?.cancel()
            stateJob = null
            scope?.cancel()
            scope = null
        }
    }

    /** Starts following the upstream state flow once; repeated calls are no-ops. */
    private fun observe(context: Context) {
        if (stateJob != null) return
        val appContext = context.applicationContext
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = newScope
        stateJob = newScope.launch {
            App.provide(appContext).commManager.connectionState.collect { upstream ->
                val mapped = map(upstream)
                val detail = (upstream as? CommManager.ConnectionState.Error)?.message
                if (mapped != lastState || detail != lastDetail) {
                    lastState = mapped
                    lastDetail = detail
                    listeners.forEach { it.onStateChanged(mapped, detail) }
                }
            }
        }
    }

    private fun map(upstream: CommManager.ConnectionState): AutoReceiver.State = when (upstream) {
        is CommManager.ConnectionState.Disconnected -> AutoReceiver.State.IDLE
        is CommManager.ConnectionState.Connected -> AutoReceiver.State.PROJECTING
        is CommManager.ConnectionState.Error -> AutoReceiver.State.ERROR
        is CommManager.ConnectionState.Connecting,
        is CommManager.ConnectionState.StartingTransport,
        is CommManager.ConnectionState.TransportStarted,
        is CommManager.ConnectionState.HandshakeComplete,
        -> AutoReceiver.State.STARTING
    }
}
