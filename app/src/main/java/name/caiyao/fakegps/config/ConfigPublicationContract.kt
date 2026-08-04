package name.caiyao.fakegps.config

internal object ConfigPublicationContract {
    fun shouldKeepLastGoodPayload(
        requestedProfileId: Long?,
        resolvedProfileId: Long?,
        clearIfMissing: Boolean,
    ): Boolean = requestedProfileId != null && resolvedProfileId == null && !clearIfMissing

    fun isCrossProcessPublishSuccessful(
        worldReadable: Boolean,
        committed: Boolean,
    ): Boolean = worldReadable && committed
}
