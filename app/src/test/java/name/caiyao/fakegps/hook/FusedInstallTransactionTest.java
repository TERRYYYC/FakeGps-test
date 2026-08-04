package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Sol R8 red-first tests: the install transaction must be TERMINAL-STATE based, not a
 * single boolean. A concurrent caller must wait for the in-flight installer's outcome
 * (never mistake "installing" for "installed"), retry after a peer's failure, and the
 * result must distinguish INSTALLED / ALREADY_INSTALLED / FAILED so evidence never
 * reports a re-discovery as a fresh install.
 */
public class FusedInstallTransactionTest {

    private static Method sampleMethod() throws Exception {
        return FusedDeliveryPlanTest.BaseCallback.class.getMethod(
                "d", FusedDeliveryPlanTest.RenamedLocationResult.class);
    }

    /** R8 #1: thread B must WAIT for A's in-flight install, then skip its own installer. */
    @Test
    public void concurrentCallerWaitsForTerminalState() throws Exception {
        FusedHookRegistry registry = new FusedHookRegistry();
        Method m = sampleMethod();
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch aFinish = new CountDownLatch(1);
        AtomicInteger installs = new AtomicInteger();

        Thread a = new Thread(() -> registry.claimAndInstall(m, method -> {
            installs.incrementAndGet();
            aStarted.countDown();
            try {
                aFinish.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        a.start();
        assertTrue(aStarted.await(2, TimeUnit.SECONDS));

        AtomicInteger bInstalls = new AtomicInteger();
        final FusedHookRegistry.InstallResult[] bResult = new FusedHookRegistry.InstallResult[1];
        Thread b = new Thread(() -> bResult[0] = registry.claimAndInstall(
                m, method -> bInstalls.incrementAndGet()));
        b.start();
        // B is now blocked inside claimAndInstall while A installs. Give it a moment to
        // prove it does NOT run its own installer nor return early with a fake success.
        Thread.sleep(200);
        assertEquals("B must not run its installer while A is installing", 0, bInstalls.get());
        assertTrue("B must still be waiting for A's outcome", b.isAlive());

        aFinish.countDown();
        b.join(2000);
        assertEquals(FusedHookRegistry.InstallResult.ALREADY_INSTALLED, bResult[0]);
        assertEquals(0, bInstalls.get());
        assertEquals(1, installs.get());
    }

    /** R8 #1: after A's install FAILS, the waiting caller retries and wins. */
    @Test
    public void waitingCallerRetriesAfterPeerFailure() throws Exception {
        FusedHookRegistry registry = new FusedHookRegistry();
        Method m = sampleMethod();
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch aFinish = new CountDownLatch(1);

        Thread a = new Thread(() -> registry.claimAndInstall(m, method -> {
            aStarted.countDown();
            try {
                aFinish.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("A's install fails");
        }));
        a.start();
        assertTrue(aStarted.await(2, TimeUnit.SECONDS));

        AtomicInteger bInstalls = new AtomicInteger();
        final FusedHookRegistry.InstallResult[] bResult = new FusedHookRegistry.InstallResult[1];
        Thread b = new Thread(() -> bResult[0] = registry.claimAndInstall(
                m, method -> bInstalls.incrementAndGet()));
        b.start();
        Thread.sleep(200);
        aFinish.countDown();
        b.join(2000);
        a.join(2000);

        assertEquals("B must retry after A's failure and install", 1, bInstalls.get());
        assertEquals(FusedHookRegistry.InstallResult.INSTALLED, bResult[0]);
        // Terminal state is now INSTALLED: a third caller must not reinstall.
        assertEquals(FusedHookRegistry.InstallResult.ALREADY_INSTALLED,
                registry.claimAndInstall(m, method -> {
                    throw new AssertionError("must not reinstall a hooked method");
                }));
    }

    /** R8 #3: tri-state — a fresh successful install reports INSTALLED exactly once. */
    @Test
    public void triStateDistinguishesFreshFromRepeat() throws Exception {
        FusedHookRegistry registry = new FusedHookRegistry();
        Method m = sampleMethod();
        assertEquals(FusedHookRegistry.InstallResult.INSTALLED,
                registry.claimAndInstall(m, method -> {}));
        assertEquals(FusedHookRegistry.InstallResult.ALREADY_INSTALLED,
                registry.claimAndInstall(m, method -> {}));
    }

    /** R8 #2: a failed install reports FAILED, leaves the method retryable, and keeps a
     *  bounded diagnostic reason for evidence. */
    @Test
    public void failureKeepsDiagnosticReason() throws Exception {
        FusedHookRegistry registry = new FusedHookRegistry();
        Method m = sampleMethod();
        assertEquals(FusedHookRegistry.InstallResult.FAILED,
                registry.claimAndInstall(m, method -> {
                    throw new IllegalArgumentException("hookMethod exploded");
                }));
        String reason = registry.lastFailure(m);
        assertTrue("failure reason must be retained for evidence, got: " + reason,
                reason != null && reason.contains("IllegalArgumentException"));
        assertEquals(FusedHookRegistry.InstallResult.INSTALLED,
                registry.claimAndInstall(m, method -> {}));
    }
}
