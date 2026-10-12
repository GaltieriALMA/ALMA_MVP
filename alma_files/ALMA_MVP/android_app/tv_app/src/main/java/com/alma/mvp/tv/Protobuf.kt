package com.alma.mvp.tv

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Just enough protobuf to speak the Android TV Remote v2 protocol.
 *
 * The protocol uses a handful of small messages, so hand-encoding them avoids
 * pulling a protobuf runtime and a code-generation step into the build.
 */

/** A decoded field value. Only the wire types this protocol uses are modelled. */
sealed interface ProtoValue {
    @JvmInline value class Num(val value: Long) : ProtoValue

    @JvmInline value class Bytes(val value: ByteArray) : ProtoValue
}

class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long) = apply {
        tag(field, 0)
        writeVarint(out, value)
    }

    fun varint(field: Int, value: Int) = varint(field, value.toLong())

    fun bool(field: Int, value: Boolean) = varint(field, if (value) 1L else 0L)

    fun bytes(field: Int, value: ByteArray) = apply {
        tag(field, 2)
        writeVarint(out, value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

    /** Nested message: encoded to its own buffer, then written length-delimited. */
    fun message(field: Int, build: ProtoWriter.() -> Unit) = apply {
        bytes(field, ProtoWriter().apply(build).toByteArray())
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) =
        writeVarint(out, ((field shl 3) or wireType).toLong())
}

/** Parses a message into field number → values. Repeated fields keep every value. */
fun parseProto(bytes: ByteArray): Map<Int, List<ProtoValue>> {
    val fields = mutableMapOf<Int, MutableList<ProtoValue>>()
    var pos = 0

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= bytes.size) throw EOFException("Truncated varint")
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw IllegalStateException("Varint too long")
        }
    }

    while (pos < bytes.size) {
        val tag = readVarint()
        val field = (tag ushr 3).toInt()
        when ((tag and 0x7).toInt()) {
            0 -> fields.getOrPut(field) { mutableListOf() }.add(ProtoValue.Num(readVarint()))
            1 -> pos += 8
            2 -> {
                val length = readVarint().toInt()
                if (length < 0 || pos + length > bytes.size) throw EOFException("Bad length")
                fields.getOrPut(field) { mutableListOf() }
                    .add(ProtoValue.Bytes(bytes.copyOfRange(pos, pos + length)))
                pos += length
            }
            5 -> pos += 4
            else -> throw IllegalStateException("Unsupported wire type in field $field")
        }
    }
    return fields
}

fun Map<Int, List<ProtoValue>>.num(field: Int): Long? =
    (this[field]?.firstOrNull() as? ProtoValue.Num)?.value

fun Map<Int, List<ProtoValue>>.bytes(field: Int): ByteArray? =
    (this[field]?.firstOrNull() as? ProtoValue.Bytes)?.value

fun Map<Int, List<ProtoValue>>.message(field: Int): Map<Int, List<ProtoValue>>? =
    bytes(field)?.let { parseProto(it) }

fun Map<Int, List<ProtoValue>>.has(field: Int): Boolean = containsKey(field)

// ------------------------------------------------------------------ framing

private fun writeVarint(out: OutputStream, value: Long) {
    var remaining = value
    while (true) {
        val bits = (remaining and 0x7F).toInt()
        remaining = remaining ushr 7
        if (remaining == 0L) {
            out.write(bits)
            return
        }
        out.write(bits or 0x80)
    }
}

/** Messages on the wire are a varint byte length followed by the payload. */
fun OutputStream.writeFramed(payload: ByteArray) {
    writeVarint(this, payload.size.toLong())
    write(payload)
    flush()
}

fun InputStream.readFramed(): ByteArray {
    var length = 0L
    var shift = 0
    while (true) {
        val b = read()
        if (b < 0) throw EOFException("Connection closed")
        length = length or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) break
        shift += 7
    }
    val payload = ByteArray(length.toInt())
    var read = 0
    while (read < payload.size) {
        val n = read(payload, read, payload.size - read)
        if (n < 0) throw EOFException("Connection closed mid-message")
        read += n
    }
    return payload
}
