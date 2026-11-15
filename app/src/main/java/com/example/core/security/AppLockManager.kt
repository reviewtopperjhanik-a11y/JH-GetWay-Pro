package com.example.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

sealed interface PinVerifyResult {
    data class Success(val upgradedHash: String? = null) : PinVerifyResult
    data class InvalidPin(
        val failedAttempts: Int,
        val lockedUntilEpochMs: Long,
        val remainingCooldownMs: Long
    ) : PinVerifyResult
    data class LockedOut(
        val failedAttempts: Int,
        val lockedUntilEpochMs: Long,
        val remainingCooldownMs: Long
    ) : PinVerifyResult
}

data class LockoutState(
    val failedAttempts: Int = 0,
    val lockedUntilEpochMs: Long = 0L
)

/**
 * Local App Lock manager using salted PBKDF2WithHmacSHA256, constant-time comparison,
 * legacy SHA-256 hash migration, and stepped attempt throttling / timed lockout.
 *
 * Biometric prompt may gate UI unlock when explicitly enabled by the user,
 * while credential storage encryption remains strictly protected by Android Keystore AES-GCM.
 */
class AppLockManager(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val PREFIX = "pbkdf2_v1"
        const val DEFAULT_ITERATIONS = 120_000
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
        private const val MIN_PIN_LENGTH = 4
        private const val MAX_PIN_LENGTH = 8
        private val LEGACY_SHA256_REGEX = Regex("^[0-9a-f]{64}$")

        fun cooldownMsForFailures(failuresAfterIncrement: Int): Long {
            return when {
                failuresAfterIncrement < 5 -> 0L
                failuresAfterIncrement == 5 -> 30_000L
                failuresAfterIncrement == 6 -> 60_000L
                else -> 300_000L
            }
        }
    }

    private val secureRandom = SecureRandom()

    fun isValidPinFormat(rawPin: String): Boolean {
        return rawPin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && rawPin.all { it.isDigit() }
    }

    fun hashPin(rawPin: String): String {
        require(isValidPinFormat(rawPin)) {
            "PIN must be $MIN_PIN_LENGTH to $MAX_PIN_LENGTH digits"
        }
        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val derived = pbkdf2(rawPin.toCharArray(), salt, iterations)
        val enc = Base64.getEncoder()
        return "$PREFIX$$iterations$${enc.encodeToString(salt)}$${enc.encodeToString(derived)}"
    }

    fun verifyPin(
        rawPin: String,
        storedHash: String,
        currentLockout: LockoutState
    ): PinVerifyResult {
        val now = clockMs()
        if (currentLockout.lockedUntilEpochMs > now) {
            return PinVerifyResult.LockedOut(
                failedAttempts = currentLockout.failedAttempts,
                lockedUntilEpochMs = currentLockout.lockedUntilEpochMs,
                remainingCooldownMs = currentLockout.lockedUntilEpochMs - now
            )
        }

        if (storedHash.isBlank()) {
            return recordFailure(currentLockout.failedAttempts, now)
        }

        // Perform verification uniformly without leaking prefix/length hints
        val matchOutcome = verifyConstantTime(rawPin, storedHash)
        if (matchOutcome.matched) {
            return PinVerifyResult.Success(upgradedHash = matchOutcome.upgradedHash)
        }

        return recordFailure(currentLockout.failedAttempts, now)
    }

    private fun recordFailure(previousFailures: Int, now: Long): PinVerifyResult {
        val newFailures = (previousFailures + 1).coerceAtLeast(1)
        val cooldown = cooldownMsForFailures(newFailures)
        val lockedUntil = if (cooldown > 0L) now + cooldown else 0L
        return if (cooldown > 0L) {
            PinVerifyResult.LockedOut(
                failedAttempts = newFailures,
                lockedUntilEpochMs = lockedUntil,
                remainingCooldownMs = cooldown
            )
        } else {
            PinVerifyResult.InvalidPin(
                failedAttempts = newFailures,
                lockedUntilEpochMs = 0L,
                remainingCooldownMs = 0L
            )
        }
    }

    private data class ConstantTimeOutcome(
        val matched: Boolean,
        val upgradedHash: String? = null
    )

    private fun verifyConstantTime(rawPin: String, storedHash: String): ConstantTimeOutcome {
        if (storedHash.startsWith("$PREFIX$")) {
            val parts = storedHash.split('$')
            if (parts.size != 4) return ConstantTimeOutcome(false)
            val parsedIterations = parts[1].toIntOrNull()?.coerceIn(10_000, 1_000_000) ?: return ConstantTimeOutcome(false)
            val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return ConstantTimeOutcome(false)
            val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return ConstantTimeOutcome(false)
            if (salt.isEmpty() || expected.isEmpty()) return ConstantTimeOutcome(false)

            val candidate = pbkdf2(rawPin.toCharArray(), salt, parsedIterations)
            val matches = rawPin.isNotEmpty() && MessageDigest.isEqual(candidate, expected)
            candidate.fill(0)
            return ConstantTimeOutcome(matched = matches)
        }

        // Transparent legacy SHA-256 upgrade path (verifies in constant time and upgrades to PBKDF2 on match)
        if (LEGACY_SHA256_REGEX.matches(storedHash)) {
            val candidateDigest = MessageDigest.getInstance("SHA-256")
                .digest(rawPin.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .toByteArray(Charsets.US_ASCII)
            val expectedBytes = storedHash.toByteArray(Charsets.US_ASCII)
            val matches = rawPin.isNotEmpty() && MessageDigest.isEqual(candidateDigest, expectedBytes)
            val upgraded = if (matches && isValidPinFormat(rawPin)) hashPin(rawPin) else null
            return ConstantTimeOutcome(matched = matches, upgradedHash = upgraded)
        }

        return ConstantTimeOutcome(false)
    }

    private fun pbkdf2(pinChars: CharArray, salt: ByteArray, iter: Int): ByteArray {
        val spec = PBEKeySpec(pinChars, salt, iter, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
            pinChars.fill('\u0000')
        }
    }
}
