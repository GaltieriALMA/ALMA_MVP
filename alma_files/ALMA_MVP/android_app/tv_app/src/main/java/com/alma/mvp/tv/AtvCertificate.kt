package com.alma.mvp.tv

import android.content.Context
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date

/**
 * The client identity used for both Android TV Remote v2 connections.
 *
 * Pairing binds the TV's trust to *this certificate*, so it is generated once
 * and kept. Losing it means pairing again from scratch.
 */
class AtvIdentity(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {
    val keyStore: KeyStore by lazy {
        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, privateKey, PASSWORD, arrayOf<java.security.cert.Certificate>(certificate))
        }
    }

    companion object {
        internal const val ALIAS = "tv-remote-client"
        internal val PASSWORD = "tv-remote".toCharArray()
    }
}

object AtvCertificateStore {

    private const val FILE_NAME = "atv_client.p12"
    private const val VALIDITY_YEARS = 20

    /** Loads the stored identity, creating and persisting one on first use. */
    @Synchronized
    fun identity(context: Context): AtvIdentity {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) {
            runCatching { load(file) }.getOrNull()?.let { return it }
            // A corrupt store is unrecoverable; start over rather than wedging.
            file.delete()
        }
        return create().also { save(it, file) }
    }

    /** Discards the identity, so the next pairing starts with a fresh one. */
    @Synchronized
    fun reset(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }

    private fun load(file: File): AtvIdentity {
        val store = KeyStore.getInstance("PKCS12")
        file.inputStream().use { store.load(it, AtvIdentity.PASSWORD) }
        val key = store.getKey(AtvIdentity.ALIAS, AtvIdentity.PASSWORD) as PrivateKey
        val cert = store.getCertificate(AtvIdentity.ALIAS) as X509Certificate
        return AtvIdentity(key, cert)
    }

    private fun save(identity: AtvIdentity, file: File) {
        file.outputStream().use { identity.keyStore.store(it, AtvIdentity.PASSWORD) }
    }

    private fun create(): AtvIdentity {
        val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()

        val name = X500NameBuilder(BCStyle.INSTANCE)
            .addRDN(BCStyle.CN, "atvremote")
            .build()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24 * 60 * 60 * 1000L)
        val notAfter = Date(now + VALIDITY_YEARS * 365L * 24 * 60 * 60 * 1000L)

        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            notBefore,
            notAfter,
            name,
            keyPair.public,
        ).addExtension(Extension.basicConstraints, false, BasicConstraints(0))

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        return AtvIdentity(keyPair.private, certificate)
    }
}

/**
 * Minimal unsigned big-endian encoding of an RSA value.
 *
 * The pairing hash is defined over the raw modulus and exponent, so Java's
 * sign-byte padding must be stripped or the digest will not match the TV's.
 */
fun BigInteger.toUnsignedBytes(): ByteArray {
    val bytes = toByteArray()
    return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
}

val X509Certificate.rsaPublicKey: RSAPublicKey get() = publicKey as RSAPublicKey
