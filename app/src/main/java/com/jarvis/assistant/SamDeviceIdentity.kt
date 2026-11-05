package com.jarvis.assistant

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.interfaces.ECPublicKey
import java.util.Locale

internal data class SamDevicePublicIdentity(
    val publicKeyBase64: String,
    val fingerprintSha256: String
)

/**
 * Per-install Android Keystore identity for future SAM device pairing.
 * The private key remains inside Android Keystore and is never exported.
 * This does not contact a gateway or mean that the phone is paired.
 */
internal class SamDeviceIdentity {
    companion object {
        private const val KEY_ALIAS = "sam.device.identity.v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }

    @Synchronized
    fun getOrCreate(): SamDevicePublicIdentity {
        var keyStore = loadKeyStore()
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEY_STORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
            keyStore = loadKeyStore()
        }

        val certificate = keyStore.getCertificate(KEY_ALIAS)
            ?: throw IllegalStateException("SAM Android Keystore certificate is unavailable.")
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
            ?: throw IllegalStateException("SAM Android Keystore private key is unavailable.")
        val publicKey = certificate.publicKey as? ECPublicKey
            ?: throw IllegalStateException("SAM device identity is not an elliptic-curve key.")

        if (publicKey.params.order.bitLength() != 256)
            throw IllegalStateException("SAM device identity is not a P-256 key.")

        verifyKeyPair(privateKey, publicKey)
        SamDeviceCommandCanonicalizer.verifyKnownVector()

        val encodedPublicKey = publicKey.encoded
            ?: throw IllegalStateException("SAM public key could not be encoded.")
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(encodedPublicKey)
            .joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xFF) }

        return SamDevicePublicIdentity(
            Base64.encodeToString(encodedPublicKey, Base64.NO_WRAP),
            fingerprint
        )
    }

    /** Creates a fresh 192-bit nonce in the gateway's unpadded base64url format. */
    fun createNonce(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Signs a canonical typed command and returns an unpadded base64url P1363 signature. */
    fun signCommand(command: SamDeviceCommand): String {
        val signature = sign(SamDeviceCommandCanonicalizer.createSigningPayload(command))
        return Base64.encodeToString(signature, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Signs canonical gateway payload bytes and returns 64-byte IEEE P1363 r||s. */
    fun sign(canonicalPayload: ByteArray): ByteArray {
        val keyStore = loadKeyStore()
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
            ?: throw IllegalStateException("Create the SAM device identity before signing.")

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(canonicalPayload)
        return EcdsaSignatureEncoding.derToP1363(signer.sign())
    }

    private fun verifyKeyPair(privateKey: PrivateKey, publicKey: ECPublicKey) {
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(challenge)
        val derSignature = signer.sign()

        // The gateway consumes P1363. Round-trip the Android provider's DER
        // signature and verify again to catch conversion defects at setup time.
        val wireSignature = EcdsaSignatureEncoding.derToP1363(derSignature)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(challenge)
        if (!verifier.verify(EcdsaSignatureEncoding.p1363ToDer(wireSignature)))
            throw IllegalStateException("SAM Android device identity failed its signing self-test.")
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
}

/** Converts Android's ASN.1 DER ECDSA output to the gateway's fixed 64-byte P1363 format. */
private object EcdsaSignatureEncoding {
    private data class Tlv(val value: ByteArray, val nextOffset: Int)

    fun derToP1363(der: ByteArray): ByteArray {
        require(der.size >= 8 && (der[0].toInt() and 0xFF) == 0x30) { "Invalid ECDSA DER sequence." }
        var offset = 1
        val sequenceLength = readShortLength(der, offset)
        offset++
        require(sequenceLength == der.size - offset) { "Invalid ECDSA DER sequence length." }

        val r = readTlv(der, offset, 0x02)
        val s = readTlv(der, r.nextOffset, 0x02)
        require(s.nextOffset == der.size) { "Unexpected data after ECDSA DER signature." }

        return toFixedWidth(r.value) + toFixedWidth(s.value)
    }

    fun p1363ToDer(p1363: ByteArray): ByteArray {
        require(p1363.size == 64) { "P1363 ECDSA P-256 signature must be 64 bytes." }
        val r = integerTlv(p1363.copyOfRange(0, 32))
        val s = integerTlv(p1363.copyOfRange(32, 64))
        val content = r + s
        require(content.size < 128) { "Unexpected P-256 ECDSA signature size." }
        return byteArrayOf(0x30, content.size.toByte()) + content
    }

    private fun readTlv(input: ByteArray, start: Int, expectedTag: Int): Tlv {
        require(start + 2 <= input.size && (input[start].toInt() and 0xFF) == expectedTag) {
            "Invalid ECDSA DER integer."
        }
        val length = readShortLength(input, start + 1)
        val valueStart = start + 2
        val valueEnd = valueStart + length
        require(length in 1..33 && valueEnd <= input.size) { "Invalid ECDSA DER integer length." }
        return Tlv(input.copyOfRange(valueStart, valueEnd), valueEnd)
    }

    private fun readShortLength(input: ByteArray, offset: Int): Int {
        require(offset < input.size) { "Missing ECDSA DER length." }
        val length = input[offset].toInt() and 0xFF
        require(length < 0x80) { "Long-form ECDSA DER lengths are not valid for P-256 signatures." }
        return length
    }

    private fun toFixedWidth(integer: ByteArray): ByteArray {
        require(integer.isNotEmpty()) { "Empty ECDSA integer." }
        require((integer[0].toInt() and 0x80) == 0) { "Negative ECDSA integer." }
        val magnitude = if (integer.size > 1 && integer[0].toInt() == 0) {
            require((integer[1].toInt() and 0x80) != 0) { "Non-canonical ECDSA integer." }
            integer.copyOfRange(1, integer.size)
        } else integer

        require(magnitude.size in 1..32) { "ECDSA integer is wider than P-256." }
        return ByteArray(32).also { magnitude.copyInto(it, 32 - magnitude.size) }
    }

    private fun integerTlv(value: ByteArray): ByteArray {
        var first = 0
        while (first < value.size - 1 && value[first].toInt() == 0) first++
        var magnitude = value.copyOfRange(first, value.size)
        require(magnitude.any { it.toInt() != 0 }) { "ECDSA integer must not be zero." }
        if ((magnitude[0].toInt() and 0x80) != 0)
            magnitude = byteArrayOf(0) + magnitude
        require(magnitude.size < 128) { "Unexpected ECDSA integer size." }
        return byteArrayOf(0x02, magnitude.size.toByte()) + magnitude
    }
}
