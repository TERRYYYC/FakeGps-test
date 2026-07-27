package name.caiyao.fakegps.config

internal object ConfigPublicationContract {
    fun isCrossProcessPublishSuccessful(
        worldReadable: Boolean,
        committed: Boolean,
    ): Boolean = worldReadable && committed
}
