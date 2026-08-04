package name.caiyao.fakegps.hook;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-Method hook dedup (Sol R5 finding #3).
 *
 * The previous per-class set had two holes: two runtime subclasses inheriting the SAME
 * implementation {@link Method} would each install a hook on it (double delivery), and a
 * class whose installation partially failed was already marked done and never retried.
 * Claiming by {@link Method} identity fixes both: {@link Method#equals} keys on declaring
 * class + signature, so an inherited method resolved through any subclass claims once, and
 * a method that failed to install is simply never claimed, leaving the retry path open.
 */
final class FusedHookRegistry {

    private final Set<Method> claimed = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** @return true if this caller won the claim and should install the hook. */
    boolean claim(Method method) {
        return claimed.add(method);
    }
}
