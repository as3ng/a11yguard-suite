package com.fraudintel.a11yguard

/**
 * Optional integration point for a server-verified device/app integrity verdict
 * (e.g. Google Play Integrity API). The SDK never trusts a purely on-device verdict for this;
 * the app supplies the result of its own server-side token verification.
 *
 * Returning [IntegrityVerdict.UNKNOWN] keeps the signal neutral.
 */
interface IntegrityProvider {
    fun deviceIntegrity(): IntegrityVerdict
}

internal object DefaultIntegrityProvider : IntegrityProvider {
    override fun deviceIntegrity(): IntegrityVerdict = IntegrityVerdict.UNKNOWN
}
