package name.caiyao.fakegps.hook;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Identity gate for fused-returned Task instances (Sol R6 #1).
 *
 * Hooking a listener-registration method on the shared runtime Task class would otherwise
 * wrap listeners for EVERY GMS Task in the process, not just the ones our fused APIs
 * returned. This tracker marks the actual instances handed out by hooked fused methods
 * (weakly, so completed tasks are never leaked), and the registration hook wraps only
 * listeners registered on marked instances.
 */
final class FusedTaskTracker {

    private final Set<Object> tracked =
            Collections.newSetFromMap(
                    Collections.synchronizedMap(new WeakHashMap<>()));

    void mark(Object task) {
        if (task != null) {
            tracked.add(task);
        }
    }

    boolean isTracked(Object task) {
        return task != null && tracked.contains(task);
    }
}
