package name.caiyao.fakegps.ui.screen.verify

import java.util.concurrent.atomic.AtomicBoolean

/** Owns the single verification attempt allowed to update one VerifyViewModel. */
internal class VerificationRefreshGate {
    private val active = AtomicBoolean(false)

    fun tryStart(): Boolean = active.compareAndSet(false, true)

    fun finish() {
        active.set(false)
    }
}
