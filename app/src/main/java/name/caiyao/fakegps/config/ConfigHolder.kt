package name.caiyao.fakegps.config

import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the currently-effective [SpoofConfig] and enforces LAST-KNOWN-GOOD semantics
 * on hot-reload.
 *
 * This is the code-level fix for the reviewer's correction: a hot-reload that fails
 * to parse must NOT revert to real device data mid-test (that would leak the true
 * environment while an app is watching). Instead:
 *
 *   - initial state          = null   => caller treats as PASSTHROUGH (first launch,
 *                                        no config yet: passing through real values is
 *                                        the safe default BEFORE any spoof is active)
 *   - update(valid json)     => replace current, return success(newConfig)
 *   - update(invalid json)   => KEEP the currently-effective config, return failure(err)
 *
 * The hook hot-path only ever reads [current] (in-memory AtomicReference); it never
 * parses JSON or touches IPC/disk.
 */
class ConfigHolder {

    private val ref = AtomicReference<SpoofConfig?>(null)

    /** Currently-effective config, or null if none has been loaded yet (=> passthrough). */
    fun current(): SpoofConfig? = ref.get()

    /**
     * Attempt to hot-reload from a JSON snapshot.
     *
     * @return [Result.success] with the new config when parsing succeeds; otherwise
     *         [Result.failure] carrying the parse error, while the previously-effective
     *         config remains untouched (last-known-good).
     */
    fun update(json: String): Result<SpoofConfig> = try {
        val parsed = ConfigCodec.fromJson(json)
        ref.set(parsed)
        Result.success(parsed)
    } catch (e: Exception) {
        // last-known-good: keep whatever was effective; never silently revert to real data.
        Result.failure(e)
    }
}
