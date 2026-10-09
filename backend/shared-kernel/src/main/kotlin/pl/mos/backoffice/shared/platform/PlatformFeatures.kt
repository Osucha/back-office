package pl.mos.backoffice.shared.platform

/**
 * Platform-wide feature switches set by administrators (Platform settings, owned by the identity context) that other
 * contexts must respect. ADR 0036: [specKitAllowed] = projects may turn Spec Kit on (default true); when false no
 * project can use Spec Kit steps, whatever its own setting says.
 */
fun interface PlatformFeatures {
    fun specKitAllowed(): Boolean

    companion object {
        /** Everything allowed (tests, installations without the identity context). */
        val DEFAULTS = PlatformFeatures { true }
    }
}
