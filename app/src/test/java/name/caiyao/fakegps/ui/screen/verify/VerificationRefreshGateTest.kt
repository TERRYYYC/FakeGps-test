package name.caiyao.fakegps.ui.screen.verify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationRefreshGateTest {
    @Test
    fun `only one refresh attempt owns the view model at a time`() {
        val gate = VerificationRefreshGate()

        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())

        gate.finish()

        assertTrue(gate.tryStart())
    }
}
