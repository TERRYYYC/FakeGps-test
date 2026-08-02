package name.caiyao.fakegps.verify

/** Decides which processes of the module APK may receive its own hooks. */
object RuntimeSelfHookPolicy {
    const val MODULE_PACKAGE = "name.caiyao.fakegps"
    const val PROBE_PROCESS = "$MODULE_PACKAGE:hook_verify"

    /**
     * Release main stays real so configuration screens never echo spoofed values as truth.
     * The private probe process is the sole release self-hook exception. Other scoped packages
     * keep the module's normal behaviour.
     */
    @JvmStatic
    fun shouldHook(
        debugBuild: Boolean,
        packageName: String?,
        processName: String?,
    ): Boolean {
        if (packageName != MODULE_PACKAGE) return true
        return debugBuild || processName == PROBE_PROCESS
    }
}
