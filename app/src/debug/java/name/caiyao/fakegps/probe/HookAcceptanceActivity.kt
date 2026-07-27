package name.caiyao.fakegps.probe

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.probe.HookAcceptanceStateMachine.Event
import name.caiyao.fakegps.probe.HookAcceptanceStateMachine.State
import java.nio.charset.StandardCharsets

/**
 * Debug-only acceptance entry point.
 *
 * It publishes a validated, one-shot cellular field map to the same XSharedPreferences transport
 * used by real target processes, waits for MainHook's initial refresh, probes public APIs, then
 * republishes the user's database-backed config. The profile database itself is never opened or
 * mutated by this activity.
 */
class HookAcceptanceActivity : Activity() {
    private var state = State.IDLE
    private var sessionId = "unparsed"
    private var restoreAttempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            sessionId = requireNotNull(intent.getStringExtra(EXTRA_SESSION_ID)) {
                "missing $EXTRA_SESSION_ID"
            }
            val encoded = requireNotNull(intent.getStringExtra(EXTRA_PAYLOAD_BASE64)) {
                "missing $EXTRA_PAYLOAD_BASE64"
            }
            val raw = String(
                Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                StandardCharsets.UTF_8,
            )
            val payload = HookAcceptancePayload.validate(sessionId, raw)
            check(publishOverride(payload.json)) { "test payload commit failed" }

            state = HookAcceptanceStateMachine.transition(state, Event.PUBLISHED)
            logState("published")
            Handler(Looper.getMainLooper()).postDelayed(
                { runProbeAndRestore() },
                PROBE_DELAY_MS,
            )
        } catch (failure: Throwable) {
            if (state == State.IDLE) {
                state = HookAcceptanceStateMachine.transition(state, Event.ABORTED)
                logState("aborted", failure)
                finish()
            } else {
                logState("probe_setup_failed", failure)
                restoreAndFinish()
            }
        }
    }

    private fun runProbeAndRestore() {
        try {
            state = HookAcceptanceStateMachine.transition(state, Event.PROBE_STARTED)
            logState("probing")
            val report = HookProbe.run(this, sessionId)
            val resultFile = cacheDir.resolve("hook-acceptance-$sessionId.json")
            resultFile.writeText(report.toString(), StandardCharsets.UTF_8)
            logState("report_ready", resultPath = resultFile.absolutePath)
        } catch (failure: Throwable) {
            logState("probe_failed", failure)
        } finally {
            restoreAndFinish()
        }
    }

    @Suppress("DEPRECATION")
    private fun publishOverride(json: String): Boolean =
        getSharedPreferences(ConfigPrefsSync.PREFS_NAME, Context.MODE_WORLD_READABLE)
            .edit()
            .putString(ConfigPrefsSync.KEY_JSON, json)
            .commit()

    private fun restoreAndFinish() {
        if (!HookAcceptanceStateMachine.requiresRestore(state) || restoreAttempted) {
            finish()
            return
        }
        restoreAttempted = true
        if (state != State.RESTORING) {
            state = HookAcceptanceStateMachine.transition(state, Event.RESTORE_STARTED)
        }
        logState("restoring")

        if (ConfigPrefsSync.sync(applicationContext)) {
            state = HookAcceptanceStateMachine.transition(state, Event.RESTORE_COMPLETED)
            logState("restored")
        } else {
            logState("restore_failed", IllegalStateException("database-backed publish failed"))
        }
        finish()
    }

    override fun onDestroy() {
        if (HookAcceptanceStateMachine.requiresRestore(state) && !restoreAttempted) {
            restoreAndFinish()
        }
        super.onDestroy()
    }

    private fun logState(
        label: String,
        failure: Throwable? = null,
        resultPath: String? = null,
    ) {
        val event = buildJsonObject {
            put("sessionId", sessionId)
            put("state", label)
            failure?.let { put("error", it.toString()) }
            resultPath?.let { put("resultPath", it) }
        }
        Log.w(TAG, event.toString())
    }

    companion object {
        const val TAG = "FakeGPSAcceptance"
        const val EXTRA_SESSION_ID = "acceptance_session_id"
        const val EXTRA_PAYLOAD_BASE64 = "acceptance_payload_base64"
        private const val PROBE_DELAY_MS = 5_000L
    }
}
