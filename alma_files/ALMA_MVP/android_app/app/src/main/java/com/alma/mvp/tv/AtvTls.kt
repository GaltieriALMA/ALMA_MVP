package com.alma.mvp.tv

import android.util.Log
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * TLS plumbing shared by the pairing and remote channels.
 *
 * The TV presents a self-signed certificate, so the usual chain validation
 * cannot apply. Trust is established instead by the pairing handshake, which
 * hashes both certificates together with the code shown on screen — an attacker
 * substituting a certificate cannot produce a matching digest.
 */
object AtvTls {

    private const val TAG = "TvRemote"

    /** Captures the peer certificate; validation is deliberately deferred to pairing. */
    internal class CapturingTrustManager : X509TrustManager {
        @Volatile var peerCertificate: X509Certificate? = null

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            peerCertificate = chain.firstOrNull()
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    class Connection internal constructor(
        val socket: SSLSocket,
        private val trustManager: CapturingTrustManager,
    ) {
        /** The TV's certificate, available once the handshake has completed. */
        val serverCertificate: X509Certificate?
            get() = trustManager.peerCertificate
                ?: socket.session.peerCertificates.firstOrNull() as? X509Certificate
    }

    fun connect(
        host: String,
        port: Int,
        identity: AtvIdentity,
        timeoutMs: Int = 5_000,
        readTimeoutMs: Int = 0,
    ): Connection {
        val keyManagers = KeyManagerFactory
            .getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(identity.keyStore, AtvIdentity.PASSWORD) }
            .keyManagers

        val trustManager = CapturingTrustManager()
        val context = SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf(trustManager), SecureRandom())
        }

        val socket = context.socketFactory.createSocket() as SSLSocket
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = readTimeoutMs
        socket.startHandshake()
        Log.d(TAG, "TLS up to $host:$port, cipher=${socket.session.cipherSuite}")
        return Connection(socket, trustManager)
    }
}
