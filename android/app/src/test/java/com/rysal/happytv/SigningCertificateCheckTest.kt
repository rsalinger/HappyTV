package com.rysal.happytv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SigningCertificateCheckTest {
    private val digest = "ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab1c"
    private val colons = digest.chunked(2).joinToString(":").uppercase()

    @Test
    fun emptyExpectedSkipsTheCheck() {
        HappyTvApp.checkSigningCertificates("", listOf(digest))
        HappyTvApp.checkSigningCertificates("", emptyList())
        HappyTvApp.checkSigningCertificates(" : ", listOf(digest, digest))
    }

    @Test
    fun matchPasses() {
        HappyTvApp.checkSigningCertificates(digest, listOf(digest))
        HappyTvApp.checkSigningCertificates(digest, listOf(digest.uppercase()))
    }

    @Test
    fun mismatchThrows() {
        val other = "0".repeat(64)
        assertThrows(RuntimeException::class.java) {
            HappyTvApp.checkSigningCertificates(digest, listOf(other))
        }
    }

    @Test
    fun missingOrMultipleCertificatesThrowWhenExpected() {
        assertThrows(RuntimeException::class.java) {
            HappyTvApp.checkSigningCertificates(digest, emptyList())
        }
        assertThrows(RuntimeException::class.java) {
            HappyTvApp.checkSigningCertificates(digest, listOf(digest, digest))
        }
    }

    @Test
    fun colonFormattedAndSpacedInputIsNormalised() {
        assertEquals(digest, HappyTvApp.normalizeCertDigest(colons))
        assertEquals(digest, HappyTvApp.normalizeCertDigest("  $colons\n"))
        HappyTvApp.checkSigningCertificates(colons, listOf(digest))
    }

    @Test
    fun theTestBuildHasNoExpectedDigest() {
        assertEquals("", BuildConfig.EXPECTED_CERT_SHA256)
    }
}
