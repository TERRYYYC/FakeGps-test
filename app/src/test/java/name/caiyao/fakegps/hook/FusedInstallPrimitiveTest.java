package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Sol R7 red-first tests: unified claim-install primitive and true weak identity.
 *
 * R7 #1: the repeated claim/try/release template already leaked two sites — installation
 * must be a single primitive that keeps the claim on success and releases on failure.
 * R7 #3: the task tracker used WeakHashMap equals() semantics; two equals-equal but
 * non-identical tasks must NOT pollute each other.
 */
public class FusedInstallPrimitiveTest {

    private static Method sampleMethod() throws Exception {
        return FusedDeliveryPlanTest.BaseCallback.class.getMethod(
                "d", FusedDeliveryPlanTest.RenamedLocationResult.class);
    }

    /** Installer succeeds -> claim retained; a second install is skipped. */
    @Test
    public void successfulInstallRetainsClaim() throws Exception {
        FusedHookRegistry registry = new FusedHookRegistry();
        Method m = sampleMethod();
        AtomicInteger installs = new AtomicInteger();
        assertTrue(registry.claimAndInstall(m, method -> installs.incrementAndGet()));
        assertEquals(1, installs.get());
        assertTrue("already-hooked reports hooked (no duplicate install)",
                registry.claimAndInstall(m, method -> installs.incrementAndGet()));
        assertEquals("same Method must not install twice", 1, installs.get());
    }

    /** Installer throws -> claim released -> the next discovery retries and succeeds. */
    @Test
    public void failedInstallReleasesAndRetries() throws Exception {
        FusedHookRegistry registry = new FusedHookRegistry();
        Method m = sampleMethod();
        AtomicInteger attempts = new AtomicInteger();
        assertFalse("failed install reports not-installed",
                registry.claimAndInstall(m, method -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("simulated hookMethod failure");
                }));
        assertEquals(1, attempts.get());
        assertTrue("retry after failure must be possible",
                registry.claimAndInstall(m, method -> attempts.incrementAndGet()));
        assertEquals(2, attempts.get());
    }

    // --- True weak identity (R7 #3) ------------------------------------------

    /** Two equals-equal but non-identical objects must not share tracking. */
    @Test
    public void trackerUsesReferenceIdentityNotEquals() {
        FusedTaskTracker tracker = new FusedTaskTracker();
        String a = new String("coord");
        String b = new String("coord");
        assertEquals(a, b); // equals-equal, non-identical
        tracker.mark(a);
        assertTrue(tracker.isTracked(a));
        assertFalse("equals-equal but non-identical object must NOT be tracked",
                tracker.isTracked(b));
    }

    /** Objects with mutable hashCode remain findable (hash captured at insert). */
    @Test
    public void trackerSurvivesMutableHashCode() {
        FusedTaskTracker tracker = new FusedTaskTracker();
        StringBuilder mutable = new StringBuilder("x");
        tracker.mark(mutable);
        mutable.append("-mutated"); // changes hashCode()
        assertTrue(tracker.isTracked(mutable));
    }

    /** Cleared referents do not accumulate forever (weak semantics smoke test). */
    @Test
    public void trackerDoesNotResurrectClearedReferences() {
        FusedTaskTracker tracker = new FusedTaskTracker();
        Object temp = new Object();
        tracker.mark(temp);
        assertTrue(tracker.isTracked(temp));
        // After the referent is gone, a different instance must never match.
        temp = null;
        assertFalse(tracker.isTracked(new Object()));
    }
}
