package com.example.core.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.VisibleForTesting
import java.security.KeyStore
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

enum class CryptoFailureReason {
    KEYSTORE_UNAVAILABLE,
    KEY_INVALIDATED,
    AUTHENTICATION_FAILED,
    CORRUPTED_CIPHERTEXT,
    UNSUPPORTED_FORMAT,
    ENCRYPTION_FAILED
}

class CryptoFailureException(
    val reason: CryptoFailureReason,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

data class CryptoMetadata(
    val version: String,
    val algorithm: String,
    val keyAlias: String
)

interface MasterKeyProvider {
    fun getOrCreateKey(): SecretKey
    fun deleteKeyIfExists()
}

object CryptoManager {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "jh_getway_vault_master_key_v1"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = 16
    private const val IV_SIZE = 12
    const val CURRENT_VERSION = "v1"

    private val androidKeystoreProvider = object : MasterKeyProvider {
        override fun getOrCreateKey(): SecretKey {
            return try {
                val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                val existing = try {
                    keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                } catch (e: KeyPermanentlyInvalidatedException) {
                    throw CryptoFailureException(
                        CryptoFailureReason.KEY_INVALIDATED,
                        "Vault master key was invalidated by device security state changes. Reset vault key to re-save credentials.",
                        e
                    )
                } catch (e: UnrecoverableKeyException) {
                    throw CryptoFailureException(
                        CryptoFailureReason.KEY_INVALIDATED,
                        "Vault master key is no longer recoverable. Reset vault key to re-save credentials.",
                        e
                    )
                }
                if (existing != null) return existing

                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            } catch (e: CryptoFailureException) {
                throw e
            } catch (e: Exception) {
                throw CryptoFailureException(
                    CryptoFailureReason.KEYSTORE_UNAVAILABLE,
                    "Android Keystore unavailable; refusing to use weak fallback encryption.",
                    e
                )
            }
        }

        override fun deleteKeyIfExists() {
            runCatching {
                val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    keyStore.deleteEntry(KEY_ALIAS)
                }
            }
        }
    }

    /**
     * Test-only ephemeral random AES-256 provider. Never deterministic and never used on real devices.
     */
    class EphemeralTestMasterKeyProvider : MasterKeyProvider {
        @Volatile
        private var key: SecretKey? = null

        override fun getOrCreateKey(): SecretKey {
            return key ?: synchronized(this) {
                key ?: KeyGenerator.getInstance("AES").apply {
                    init(256, SecureRandom())
                }.generateKey().also { key = it }
            }
        }

        override fun deleteKeyIfExists() {
            synchronized(this) { key = null }
        }
    }

    @Volatile
    private var overrideProvider: MasterKeyProvider? = null

    @Volatile
    private var testDefaultProvider: EphemeralTestMasterKeyProvider? = null

    private fun isExplicitTestEnvironment(): Boolean {
        if (System.getProperty("jhgetway.test.mode") == "true") return true
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) return true
        return runCatching {
            Class.forName("org.robolectric.Robolectric")
            true
        }.getOrDefault(false)
    }

    private fun activeProvider(): MasterKeyProvider {
        overrideProvider?.let { return it }
        if (isExplicitTestEnvironment()) {
            return testDefaultProvider ?: synchronized(this) {
                testDefaultProvider ?: EphemeralTestMasterKeyProvider().also { testDefaultProvider = it }
            }
        }
        return androidKeystoreProvider
    }

    @VisibleForTesting
    internal fun setMasterKeyProviderForTest(provider: MasterKeyProvider?) {
        overrideProvider = provider
    }

    @VisibleForTesting
    internal fun resetTestKeyProvider() {
        overrideProvider = null
        testDefaultProvider?.deleteKeyIfExists()
        testDefaultProvider = null
    }

    fun isUsingKeystore(): Boolean = overrideProvider == null && !isExplicitTestEnvironment()

    fun parseMetadata(encoded: String): CryptoMetadata? {
        if (!encoded.startsWith("$CURRENT_VERSION:")) return null
        return CryptoMetadata(
            version = CURRENT_VERSION,
            algorithm = AES_GCM,
            keyAlias = KEY_ALIAS
        )
    }

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val key = activeProvider().getOrCreateKey()
        return try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
            "$CURRENT_VERSION:" + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw CryptoFailureException(
                CryptoFailureReason.KEY_INVALIDATED,
                "Vault master key invalidated; reset key and re-enter credentials.",
                e
            )
        } catch (e: CryptoFailureException) {
            throw e
        } catch (e: Exception) {
            throw CryptoFailureException(
                CryptoFailureReason.ENCRYPTION_FAILED,
                "Failed to encrypt credential with Keystore.",
                e
            )
        }
    }

    fun decrypt(cipherTextEncoded: String): String {
        if (cipherTextEncoded.isEmpty()) return ""
        if (!cipherTextEncoded.startsWith("$CURRENT_VERSION:")) {
            throw CryptoFailureException(
                CryptoFailureReason.UNSUPPORTED_FORMAT,
                "Unsupported or corrupted encrypted credential format."
            )
        }
        val combined = try {
            Base64.decode(cipherTextEncoded.removePrefix("$CURRENT_VERSION:"), Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw CryptoFailureException(
                CryptoFailureReason.CORRUPTED_CIPHERTEXT,
                "Corrupted Base64 credential payload.",
                e
            )
        }
        if (combined.size <= IV_SIZE + GCM_TAG_BYTES) {
            throw CryptoFailureException(
                CryptoFailureReason.CORRUPTED_CIPHERTEXT,
                "Truncated or corrupted encrypted credential payload."
            )
        }
        val iv = combined.copyOfRange(0, IV_SIZE)
        val cipherBytes = combined.copyOfRange(IV_SIZE, combined.size)
        val key = activeProvider().getOrCreateKey()
        return try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            throw CryptoFailureException(
                CryptoFailureReason.AUTHENTICATION_FAILED,
                "Credential authentication tag check failed (wrong key or tampered ciphertext).",
                e
            )
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw CryptoFailureException(
                CryptoFailureReason.KEY_INVALIDATED,
                "Vault master key invalidated; old credentials cannot be decrypted until reset.",
                e
            )
        } catch (e: CryptoFailureException) {
            throw e
        } catch (e: Exception) {
            throw CryptoFailureException(
                CryptoFailureReason.CORRUPTED_CIPHERTEXT,
                "Failed to decrypt credential.",
                e
            )
        }
    }

    fun decryptResult(cipherTextEncoded: String): Result<String> = runCatching { decrypt(cipherTextEncoded) }

    fun resetInvalidatedMasterKey(): Result<Unit> = runCatching {
        activeProvider().deleteKeyIfExists()
    }

    const val BACKUP_FORMAT_V1 = "JHBACKUP_V1"
    const val BACKUP_FORMAT_V2 = "JHBACKUP_V2"
    const val BENCHMARKED_BACKUP_KDF_ITERATIONS = 150_000
    const val MIN_BACKUP_V2_PASSPHRASE_LEN = 8
    private const val BACKUP_SALT_BYTES = 16

    data class DecryptedBackupEnvelope(
        val formatVersion: Int,
        val kdfAlgorithm: String,
        val kdfIterations: Int,
        val plainJson: String
    )

    fun encryptBackupArchive(
        plainText: String,
        passphrase: String,
        iterations: Int = BENCHMARKED_BACKUP_KDF_ITERATIONS
    ): String {
        require(passphrase.length >= MIN_BACKUP_V2_PASSPHRASE_LEN) {
            "Backup passphrase must be at least $MIN_BACKUP_V2_PASSPHRASE_LEN characters"
        }
        val boundedIterations = iterations.coerceIn(100_000, 1_000_000)
        val salt = ByteArray(BACKUP_SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val header = "$BACKUP_FORMAT_V2:$boundedIterations"
        val key = deriveKey(passphrase, salt, boundedIterations)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(header.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return listOf(
            BACKUP_FORMAT_V2,
            boundedIterations.toString(),
            Base64.encodeToString(salt, Base64.NO_WRAP),
            Base64.encodeToString(iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        ).joinToString(":")
    }

    fun decryptBackupArchive(payload: String, passphrase: String): DecryptedBackupEnvelope {
        require(passphrase.isNotEmpty()) { "Backup passphrase is required" }
        val parts = payload.trim().split(":")
        return when {
            parts.size == 5 && parts[0] == BACKUP_FORMAT_V2 -> {
                val iterations = parts[1].toIntOrNull()
                    ?.takeIf { it in 10_000..1_000_000 }
                    ?: throw CryptoFailureException(
                        CryptoFailureReason.UNSUPPORTED_FORMAT,
                        "Unsupported backup KDF parameters."
                    )
                val salt = runCatching { Base64.decode(parts[2], Base64.NO_WRAP) }.getOrElse {
                    throw CryptoFailureException(CryptoFailureReason.CORRUPTED_CIPHERTEXT, "Corrupted backup salt.", it)
                }
                val iv = runCatching { Base64.decode(parts[3], Base64.NO_WRAP) }.getOrElse {
                    throw CryptoFailureException(CryptoFailureReason.CORRUPTED_CIPHERTEXT, "Corrupted backup IV.", it)
                }
                val encrypted = runCatching { Base64.decode(parts[4], Base64.NO_WRAP) }.getOrElse {
                    throw CryptoFailureException(CryptoFailureReason.CORRUPTED_CIPHERTEXT, "Corrupted backup payload.", it)
                }
                if (salt.size != BACKUP_SALT_BYTES || iv.size != IV_SIZE || encrypted.size <= GCM_TAG_BYTES) {
                    throw CryptoFailureException(
                        CryptoFailureReason.CORRUPTED_CIPHERTEXT,
                        "Backup archive integrity check failed (invalid block lengths)."
                    )
                }
                val key = deriveKey(passphrase, salt, iterations)
                val header = "$BACKUP_FORMAT_V2:$iterations"
                val plain = try {
                    val cipher = Cipher.getInstance(AES_GCM)
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                    cipher.updateAAD(header.toByteArray(Charsets.UTF_8))
                    String(cipher.doFinal(encrypted), Charsets.UTF_8)
                } catch (e: AEADBadTagException) {
                    throw CryptoFailureException(
                        CryptoFailureReason.AUTHENTICATION_FAILED,
                        "Backup decryption failed: wrong passphrase or corrupted archive.",
                        e
                    )
                } catch (e: Exception) {
                    throw CryptoFailureException(
                        CryptoFailureReason.CORRUPTED_CIPHERTEXT,
                        "Backup archive could not be decrypted.",
                        e
                    )
                }
                DecryptedBackupEnvelope(
                    formatVersion = 2,
                    kdfAlgorithm = "PBKDF2WithHmacSHA256",
                    kdfIterations = iterations,
                    plainJson = plain
                )
            }
            parts.size == 4 && parts[0] == BACKUP_FORMAT_V1 -> {
                val plain = decryptWithPassphrase(payload, passphrase)
                DecryptedBackupEnvelope(
                    formatVersion = 1,
                    kdfAlgorithm = "PBKDF2WithHmacSHA256",
                    kdfIterations = 65_536,
                    plainJson = plain
                )
            }
            else -> throw CryptoFailureException(
                CryptoFailureReason.UNSUPPORTED_FORMAT,
                "Invalid or unsupported backup format."
            )
        }
    }

    fun encryptWithPassphrase(plainText: String, passphrase: String): String {
        require(passphrase.length >= 4) { "Passphrase must be at least 4 characters" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt, 65536)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return listOf(
            BACKUP_FORMAT_V1,
            Base64.encodeToString(salt, Base64.NO_WRAP),
            Base64.encodeToString(iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        ).joinToString(":")
    }

    fun decryptWithPassphrase(payload: String, passphrase: String): String {
        val parts = payload.trim().split(":")
        require(parts.size == 4 && parts[0] == BACKUP_FORMAT_V1) { "Invalid backup format" }
        val salt = Base64.decode(parts[1], Base64.NO_WRAP)
        val iv = Base64.decode(parts[2], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[3], Base64.NO_WRAP)
        if (salt.size != BACKUP_SALT_BYTES || iv.size != IV_SIZE || encrypted.size <= GCM_TAG_BYTES) {
            throw CryptoFailureException(
                CryptoFailureReason.CORRUPTED_CIPHERTEXT,
                "Backup archive integrity check failed."
            )
        }
        val key = deriveKey(passphrase, salt, 65536)
        return try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            throw CryptoFailureException(
                CryptoFailureReason.AUTHENTICATION_FAILED,
                "Backup decryption failed: wrong passphrase or corrupted archive.",
                e
            )
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int = 65536): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val chars = passphrase.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, 256)
        return try {
            val derived = factory.generateSecret(spec).encoded
            try {
                SecretKeySpec(derived, "AES")
            } finally {
                derived.fill(0)
            }
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    fun generateGatewayToken(prefix: String = "jhgw"): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val encoded = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "${prefix}_${encoded}"
    }
}
