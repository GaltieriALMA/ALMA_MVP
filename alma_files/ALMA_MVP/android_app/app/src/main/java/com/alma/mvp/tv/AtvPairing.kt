package com.alma.mvp.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.security.MessageDigest

/**
 * The Android TV pairing handshake (port 6467).
 *
 * Runs in two halves because the user has to read a code off the TV in between:
 * [start] gets far enough that the TV displays its six-digit code, then
 * [complete] proves the client saw it.
 *
 * Both halves share one connection, so the session object must be kept alive
 * between them.
 */
class AtvPairingSession(
    private val host: String,
    private val identity: AtvIdentity,
) : Closeable {

    private var connection: AtvTls.Connection? = null

    /** Opens the connection and drives the handshake until the TV shows its code. */
    suspend fun start(clientName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = AtvTls.connect(host, PAIRING_PORT, identity)
            connection = conn
            val input = conn.socket.inputStream
            val output = conn.socket.outputStream

            output.writeFramed(
                outerMessage {
                    message(FIELD_PAIRING_REQUEST) {
                        string(1, "atvremote")
                        string(2, clientName)
                    }
                }
            )

            // Drive the exchange until the configuration is acknowledged, which
            // is the point at which the TV puts the code on screen.
            while (true) {
                val msg = parseProto(input.readFramed())
                checkStatus(msg)

                when {
                    msg.has(FIELD_PAIRING_REQUEST_ACK) -> output.writeFramed(
                        outerMessage {
                            message(FIELD_OPTIONS) {
                                message(1) { // input_encodings
                                    varint(1, ENCODING_HEXADECIMAL)
                                    varint(2, CODE_LENGTH)
                                }
                                varint(3, ROLE_INPUT) // preferred_role
                            }
                        }
                    )

                    msg.has(FIELD_OPTIONS) -> output.writeFramed(
                        outerMessage {
                            message(FIELD_CONFIGURATION) {
                                message(1) { // encoding
                                    varint(1, ENCODING_HEXADECIMAL)
                                    varint(2, CODE_LENGTH)
                                }
                                varint(2, ROLE_INPUT) // client_role
                            }
                        }
                    )

                    msg.has(FIELD_CONFIGURATION_ACK) -> return@runCatching

                    else -> error("Unexpected pairing message from the TV")
                }
            }
        }.onFailure { close() }
    }

    /**
     * Finishes pairing with the code displayed on the TV.
     *
     * The code's first byte is a checksum over both certificates, so a mistyped
     * code is caught here rather than by a bare rejection from the TV.
     */
    suspend fun complete(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        // A typo is caught locally, before anything is sent. The session stays
        // open in that case so the user can retype rather than re-pair.
        val digest = runCatching { digestFor(code) }
            .getOrElse { return@withContext Result.failure(it) }

        runCatching {
            val conn = connection ?: error("Pairing has not been started")
            conn.socket.outputStream.writeFramed(
                outerMessage { message(FIELD_SECRET) { bytes(1, digest) } }
            )

            val reply = parseProto(conn.socket.inputStream.readFramed())
            checkStatus(reply)
            if (!reply.has(FIELD_SECRET_ACK)) error("The TV rejected the pairing code")
        }.also { close() }
    }

    /** Builds the pairing proof, verifying the code's built-in checksum byte. */
    private fun digestFor(code: String): ByteArray {
        val conn = connection ?: error("Pairing has not been started")
        val normalised = code.trim().uppercase()

        require(normalised.length == 6) { "The code is six characters" }
        require(normalised.all { it.isDigit() || it in 'A'..'F' }) {
            "The code may only contain 0-9 and A-F"
        }

        val serverCertificate = conn.serverCertificate ?: error("TV certificate unavailable")
        val client = identity.certificate.rsaPublicKey
        val server = serverCertificate.rsaPublicKey

        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(client.modulus.toUnsignedBytes())
            update(client.publicExponent.toUnsignedBytes())
            update(server.modulus.toUnsignedBytes())
            update(server.publicExponent.toUnsignedBytes())
            update(normalised.substring(2).hexToBytes())
        }.digest()

        // The leading byte of the code is a digest checksum, so a mistyped code
        // is rejected here instead of by a bare failure from the TV.
        if (digest[0].toInt() and 0xFF != normalised.substring(0, 2).toInt(16)) {
            error("That code does not match — check the digits on the TV")
        }
        return digest
    }

    override fun close() {
        runCatching { connection?.socket?.close() }
        connection = null
    }

    private fun checkStatus(msg: Map<Int, List<ProtoValue>>) {
        val status = msg.num(FIELD_STATUS) ?: STATUS_OK.toLong()
        if (status != STATUS_OK.toLong()) {
            error(
                when (status.toInt()) {
                    STATUS_BAD_CONFIGURATION -> "The TV rejected the pairing configuration"
                    STATUS_BAD_SECRET -> "The TV rejected the pairing code"
                    else -> "Pairing failed (status $status)"
                }
            )
        }
    }

    private fun outerMessage(build: ProtoWriter.() -> Unit): ByteArray =
        ProtoWriter()
            .varint(FIELD_PROTOCOL_VERSION, PROTOCOL_VERSION)
            .varint(FIELD_STATUS, STATUS_OK)
            .apply(build)
            .toByteArray()

    private companion object {
        const val PAIRING_PORT = 6467
        const val PROTOCOL_VERSION = 2
        const val CODE_LENGTH = 6

        const val FIELD_PROTOCOL_VERSION = 1
        const val FIELD_STATUS = 2
        const val FIELD_PAIRING_REQUEST = 10
        const val FIELD_PAIRING_REQUEST_ACK = 11
        const val FIELD_OPTIONS = 20
        const val FIELD_CONFIGURATION = 30
        const val FIELD_CONFIGURATION_ACK = 31
        const val FIELD_SECRET = 40
        const val FIELD_SECRET_ACK = 41

        const val STATUS_OK = 200
        const val STATUS_BAD_CONFIGURATION = 401
        const val STATUS_BAD_SECRET = 402

        const val ENCODING_HEXADECIMAL = 3
        const val ROLE_INPUT = 1
    }
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have an even length" }
    return ByteArray(length / 2) { i ->
        substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
