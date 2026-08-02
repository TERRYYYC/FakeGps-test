package name.caiyao.fakegps.verify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import android.util.Log
import java.util.concurrent.Executors
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.config.TransportSchemaContract

/** Non-exported one-shot service running in `:hook_verify`. */
class HookVerificationService : Service() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        processTermination.cancelPending()
        val receiver = intent?.resultReceiver(EXTRA_RECEIVER)
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
        val expectedFingerprint = intent?.getStringExtra(EXTRA_FINGERPRINT)
        if (receiver == null || requestId.isNullOrBlank() || expectedFingerprint.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val request = ProbeRequest(requestId, expectedFingerprint)
        Log.i(RuntimeEvidence.PROBE_TAG, RuntimeEvidence.probeStarted(request))

        executor.execute {
            runCatching {
                runProbe(request.requestId, request.fingerprint)
            }.fold(
                onSuccess = { observation ->
                    receiver.send(
                        RESULT_OK,
                        Bundle().apply {
                            putString(EXTRA_OBSERVATION, ProbeObservationCodec.encode(observation))
                        },
                    )
                },
                onFailure = { failure ->
                    val classified = (failure as? ProbeException)?.failure
                        ?: ProbeFailure.INTERNAL_ERROR
                    receiver.send(
                        RESULT_FAILED,
                        Bundle().apply { putString(EXTRA_FAILURE, classified.name) },
                    )
                },
            )
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        // A main-process timeout stops the service before its worker reports. The probe process is
        // otherwise still alive with a hook scheduler, so every exit path must converge here.
        processTermination.schedule(PROCESS_TERMINATION_DELAY_MS)
        super.onDestroy()
    }

    private fun runProbe(
        requestId: String,
        expectedFingerprint: String,
    ): ProbeObservationEnvelope {
        if (!RuntimeHookSentinel.isHookActive()) {
            throw ProbeException(ProbeFailure.NOT_SCOPED)
        }
        val raw = ConfigPrefsSync.readPublished(applicationContext).textOrNull
            ?: throw ProbeException(ProbeFailure.INTERNAL_ERROR)
        val parsed = PublishedConfig.parse(raw)
            ?: throw ProbeException(ProbeFailure.INTERNAL_ERROR)
        if (!TransportSchemaContract.supports(parsed.schemaVersion)) {
            throw ProbeException(ProbeFailure.INTERNAL_ERROR)
        }
        val actualFingerprint = PublishedConfig.fingerprint(raw)
        if (actualFingerprint != expectedFingerprint) {
            throw ProbeException(ProbeFailure.PAYLOAD_MISMATCH)
        }
        val observation = DeviceObserver(
            applicationContext,
            configuredColumns = parsed.fields.keys + parsed.unavailable,
            unavailableColumns = parsed.unavailable,
        ).observe()
        return ProbeObservationEnvelope(
            requestId = requestId,
            fingerprint = actualFingerprint,
            values = observation.values,
            notes = observation.notes,
            cellCount = observation.cellCount,
        )
    }

    private class ProbeException(val failure: ProbeFailure) : IllegalStateException(failure.name)

    companion object {
        const val EXTRA_RECEIVER = "probe.receiver"
        const val EXTRA_REQUEST_ID = "probe.request_id"
        const val EXTRA_FINGERPRINT = "probe.fingerprint"
        const val EXTRA_OBSERVATION = "probe.observation"
        const val EXTRA_FAILURE = "probe.failure"
        const val RESULT_OK = 1
        const val RESULT_FAILED = 2
        private const val PROCESS_TERMINATION_DELAY_MS = 500L
        private val processTermination by lazy {
            val handler = Handler(Looper.getMainLooper())
            DeferredProcessTermination(
                postDelayed = { callback, delayMs -> handler.postDelayed(callback, delayMs) },
                removeCallback = { callback -> handler.removeCallbacks(callback) },
                terminate = { Process.killProcess(Process.myPid()) },
            )
        }

        fun intent(context: Context, request: ProbeRequest): Intent =
            Intent(context, HookVerificationService::class.java)
                .putExtra(EXTRA_REQUEST_ID, request.requestId)
                .putExtra(EXTRA_FINGERPRINT, request.fingerprint)
    }
}

@Suppress("DEPRECATION")
private fun Intent.resultReceiver(key: String): ResultReceiver? =
    getParcelableExtra(key) as? ResultReceiver

internal class DeferredProcessTermination(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallback: (Runnable) -> Unit,
    private val terminate: () -> Unit,
) {
    private var pending: Runnable? = null

    @Synchronized
    fun cancelPending() {
        pending?.let(removeCallback)
        pending = null
    }

    @Synchronized
    fun schedule(delayMs: Long) {
        cancelPending()
        lateinit var callback: Runnable
        callback = Runnable {
            synchronized(this) {
                if (pending !== callback) return@synchronized
                pending = null
                terminate()
            }
        }
        pending = callback
        postDelayed(callback, delayMs)
    }
}
