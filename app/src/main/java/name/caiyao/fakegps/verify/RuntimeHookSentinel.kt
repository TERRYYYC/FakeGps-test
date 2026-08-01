package name.caiyao.fakegps.verify

/**
 * Returns false in ordinary app execution.
 *
 * MainHook replaces this method on the probe process' target classloader. That makes the answer
 * evidence of a real Xposed installation rather than a static shared between unrelated
 * classloaders.
 */
object RuntimeHookSentinel {
    @JvmStatic
    fun isHookActive(): Boolean = false
}
