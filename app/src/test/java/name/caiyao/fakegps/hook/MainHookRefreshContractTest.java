package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Ownership and last-known-good rules for MainHook's per-process refresh loop. */
public class MainHookRefreshContractTest {

    @Test
    public void duplicateLoadPackageCallbacksClaimOnlyOneScheduler() {
        HookRuntimeOwnership ownership = new HookRuntimeOwnership();

        assertTrue(ownership.claimScheduler());
        assertFalse(ownership.claimScheduler());
        assertFalse(ownership.claimScheduler());
    }

    @Test
    public void hookRegistrationIsIdempotentPerTargetClassLoader() {
        HookRuntimeOwnership ownership = new HookRuntimeOwnership();
        ClassLoader first = new ClassLoader() {};
        ClassLoader second = new ClassLoader() {};

        assertTrue(ownership.claimHooks(first));
        assertFalse(ownership.claimHooks(first));
        assertTrue(ownership.claimHooks(second));
    }

    @Test
    public void rejectedPayloadKeepsLastKnownGoodDelay() {
        HookRefreshScheduler scheduler = new HookRefreshScheduler();
        assertEquals(30_000L, scheduler.currentDelayMs());
        assertEquals(60_000L, scheduler.acceptPayloadInterval(60, true));

        assertEquals(60_000L, scheduler.acceptPayloadInterval(5, false));
        assertEquals(60_000L, scheduler.currentDelayMs());
    }

    @Test
    public void acceptedPayloadUpdatesSubsequentTicksAndInitialTickStaysFast() {
        HookRefreshScheduler scheduler = new HookRefreshScheduler();

        assertEquals(3_000L, HookRefreshScheduler.INITIAL_DELAY_MS);
        assertEquals(5_000L, scheduler.acceptPayloadInterval(5, true));
        assertEquals(5_000L, scheduler.currentDelayMs());
    }

    @Test
    public void productionWriterAndHookBothReferenceTheTransportIntervalField() throws Exception {
        String writer = classBytecode("name.caiyao.fakegps.config.ConfigPrefsSync");
        String hook = classBytecode("name.caiyao.fakegps.hook.MainHook");
        String timerCallback = classBytecode("name.caiyao.fakegps.hook.MainHook$1");

        assertTrue(writer.contains("refreshIntervalSec"));
        assertTrue(writer.contains("readRefreshIntervalSec"));
        assertTrue(hook.contains("refreshIntervalSec"));
        assertTrue(hook.contains("claimScheduler"));
        assertTrue(hook.contains("claimHooks"));
        assertTrue(timerCallback.contains("currentDelayMs"));
    }

    private static String classBytecode(String className) throws Exception {
        String resource = className.replace('.', '/') + ".class";
        try (InputStream input = MainHookRefreshContractTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(resource, input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }
}
