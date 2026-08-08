package name.caiyao.fakegps.config

internal object ConfigPublicationContract {
    fun shouldKeepLastGoodPayload(
        requestedProfileId: Long?,
        resolvedProfileId: Long?,
        clearIfMissing: Boolean,
    ): Boolean = requestedProfileId != null && resolvedProfileId == null && !clearIfMissing

    /**
     * A publish only reaches the hook when the committed file is ACTUALLY readable by the target
     * app's UID. Historically the first argument was `worldReadable` — merely "MODE_WORLD_READABLE
     * did not throw". On Android N+ that call no longer applies the other-read bit, and the Vector
     * prefs mirror is written 0660, so the proxy over-reported success while the hook got
     * Permission denied. The caller must pass the VERIFIED other-read state of the committed file
     * ([isOtherReadable] over its `stat` mode), not the throw-proxy.
     */
    fun isCrossProcessPublishSuccessful(
        crossProcessReadable: Boolean,
        committed: Boolean,
    ): Boolean = crossProcessReadable && committed

    /** POSIX other-read (S_IROTH, 0o004) test over a `stat` st_mode. */
    fun isOtherReadable(stMode: Int): Boolean = (stMode and S_IROTH) != 0

    private const val S_IROTH = 0b100 // POSIX 0o004
}
