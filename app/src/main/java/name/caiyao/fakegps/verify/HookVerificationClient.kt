package name.caiyao.fakegps.verify

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ProbeClientResult {
    data class Delivered(val observation: ProbeObservationEnvelope) : ProbeClientResult
    data class Failed(val failure: ProbeFailure) : ProbeClientResult
}

/** Starts the private probe process and accepts exactly one cross-process result. */
object HookVerificationClient {
    private const val TIMEOUT_MS = 12_000L

    suspend fun request(context: Context, request: ProbeRequest): ProbeClientResult {
        val app = context.applicationContext
        val intent = HookVerificationService.intent(app, request)
        val result = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val completed = AtomicBoolean(false)
                val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        if (!completed.compareAndSet(false, true) || !continuation.isActive) return
                        continuation.resume(decodeResult(resultCode, resultData))
                    }
                }
                intent.putExtra(HookVerificationService.EXTRA_RECEIVER, receiver)
                val started = runCatching { app.startService(intent) }.getOrNull()
                if (started == null && completed.compareAndSet(false, true)) {
                    continuation.resume(ProbeClientResult.Failed(ProbeFailure.START_FAILED))
                }
                continuation.invokeOnCancellation {
                    completed.set(true)
                    app.stopService(intent)
                }
            }
        }
        if (result != null) return result
        app.stopService(intent)
        return ProbeClientResult.Failed(ProbeFailure.TIMEOUT)
    }

    private fun decodeResult(resultCode: Int, data: Bundle?): ProbeClientResult {
        if (resultCode == HookVerificationService.RESULT_OK) {
            val decoded = ProbeObservationCodec.decode(
                data?.getString(HookVerificationService.EXTRA_OBSERVATION),
            )
            return decoded?.let(ProbeClientResult::Delivered)
                ?: ProbeClientResult.Failed(ProbeFailure.MALFORMED_RESULT)
        }
        val failure = data?.getString(HookVerificationService.EXTRA_FAILURE)
            ?.let { runCatching { ProbeFailure.valueOf(it) }.getOrNull() }
            ?: ProbeFailure.INTERNAL_ERROR
        return ProbeClientResult.Failed(failure)
    }
}
