package com.fraudintel.a11yguard

interface IntegrityProvider {
    fun deviceIntegrity(): IntegrityVerdict
}

internal object DefaultIntegrityProvider : IntegrityProvider {
    override fun deviceIntegrity(): IntegrityVerdict = IntegrityVerdict.UNKNOWN
}
