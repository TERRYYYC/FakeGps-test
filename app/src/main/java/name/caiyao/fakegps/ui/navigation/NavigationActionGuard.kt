package name.caiyao.fakegps.ui.navigation

import androidx.lifecycle.Lifecycle

/** Prevents an outgoing destination from enqueueing another navigation action mid-transition. */
internal object NavigationActionGuard {
    fun runWhenResumed(state: Lifecycle.State, action: () -> Unit): Boolean {
        if (state != Lifecycle.State.RESUMED) return false
        action()
        return true
    }
}
