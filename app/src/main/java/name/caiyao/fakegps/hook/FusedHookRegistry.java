package name.caiyao.fakegps.hook;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-Method hook dedup (Sol R5 #3) with a unified install transaction (Sol R7 #1).
 *
 * The repeated claim/try/release template leaked sites twice in review — installation is
 * therefore a single primitive: {@link #claimAndInstall} claims, runs the installer, keeps
 * the claim on success, and releases on failure so the next discovery retries.
 */
final class FusedHookRegistry {

    /** Runs the actual hook installation; any throwable marks the install as failed. */
    interface Installer {
        void install(Method method);
    }

    private final Set<Method> claimed = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** @return true if this caller won the claim and should install the hook. */
    boolean claim(Method method) {
        return claimed.add(method);
    }

    /**
     * Undo a claim after a failed installation so the next discovery retries the method
     * (Sol R6 #3: claim-then-fail without release permanently skips the surface).
     */
    void release(Method method) {
        claimed.remove(method);
    }

    /**
     * The ONLY way production code installs a hooked Method: claim, install, keep on
     * success, release on failure (Sol R7 #1 — no site may open-code the template).
     *
     * @return true when the method is now hooked (or already was); false when the
     *         installer failed and the claim was released for a later retry.
     */
    boolean claimAndInstall(Method method, Installer installer) {
        if (!claim(method)) {
            return true; // already hooked by an earlier discovery
        }
        try {
            installer.install(method);
            return true;
        } catch (Throwable t) {
            release(method);
            return false;
        }
    }
}
