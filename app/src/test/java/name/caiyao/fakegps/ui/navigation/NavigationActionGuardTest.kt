package name.caiyao.fakegps.ui.navigation

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationActionGuardTest {

    @Test
    fun resumedDestination_acceptsTheNavigationAction() {
        var invoked = false

        val accepted = NavigationActionGuard.runWhenResumed(Lifecycle.State.RESUMED) {
            invoked = true
        }

        assertTrue(accepted)
        assertTrue(invoked)
    }

    @Test
    fun destinationLeavingDuringTransition_rejectsASecondNavigationAction() {
        var invoked = false

        val accepted = NavigationActionGuard.runWhenResumed(Lifecycle.State.STARTED) {
            invoked = true
        }

        assertFalse(accepted)
        assertFalse(invoked)
    }
}
