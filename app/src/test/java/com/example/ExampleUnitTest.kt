package com.example

import com.example.core.security.MaskingUtils
import com.example.core.security.NetworkAddressClassifier
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun masking_stripsSecretsFromErrors() {
        val secret = "sk-testsecretkey123456789"
        val masked = MaskingUtils.maskSecret(secret)
        assertFalse(masked.contains("secretkey"))
        assertTrue(masked.contains("••••"))

        val err = MaskingUtils.sanitizeErrorMessage("Auth failed for key $secret at ?key=AIzaSyDummyKey123456", listOf(secret))
        assertFalse(err.contains(secret))
        assertFalse(err.contains("AIzaSyDummyKey"))
    }

    @Test
    fun networkSafety_distinguishesPrivateAndPublicHosts() {
        assertTrue(NetworkAddressClassifier.isPrivateOrLoopbackHost("http://127.0.0.1:11434"))
        assertTrue(NetworkAddressClassifier.isPrivateOrLoopbackHost("http://192.168.1.50:8080"))
        assertFalse(NetworkAddressClassifier.isPrivateOrLoopbackHost("https://api.openai.com/v1"))

        // Remote HTTP blocked, HTTPS allowed
        assertFalse(NetworkAddressClassifier.isSecureOrAllowedLocalUrl("http://api.openai.com/v1", allowLocalHttp = true))
        assertTrue(NetworkAddressClassifier.isSecureOrAllowedLocalUrl("https://api.openai.com/v1", allowLocalHttp = false))

        // Local HTTP gated by explicit consent
        assertFalse(NetworkAddressClassifier.isSecureOrAllowedLocalUrl("http://127.0.0.1:11434", allowLocalHttp = false))
        assertTrue(NetworkAddressClassifier.isSecureOrAllowedLocalUrl("http://127.0.0.1:11434", allowLocalHttp = true))
    }
}
