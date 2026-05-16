package lilmuff1.bsml.protocol

import java.io.ByteArrayOutputStream

class ByteQueue {
    private var buffer = ByteArray(4096)
    private var start = 0
    private var end = 0

    val size: Int
        get() = end - start

    fun append(chunk: ByteArray) {
        ensureCapacity(chunk.size)
        chunk.copyInto(buffer, end)
        end += chunk.size
    }

    fun peek(count: Int): ByteArray = buffer.copyOfRange(start, start + count)

    fun skip(count: Int) {
        start += count
        compactIfNeeded()
    }

    fun read(count: Int): ByteArray {
        val out = buffer.copyOfRange(start, start + count)
        skip(count)
        return out
    }

    private fun ensureCapacity(incoming: Int) {
        val required = size + incoming
        if (required <= buffer.size) {
            if (end + incoming <= buffer.size) return
            compact()
            return
        }

        var newSize = buffer.size
        while (newSize < required) {
            newSize *= 2
        }

        val newBuffer = ByteArray(newSize)
        val currentSize = size
        buffer.copyInto(newBuffer, destinationOffset = 0, startIndex = start, endIndex = end)
        buffer = newBuffer
        start = 0
        end = currentSize
    }

    private fun compactIfNeeded() {
        if (start == end) {
            start = 0
            end = 0
            return
        }

        if (start >= buffer.size / 2) {
            compact()
        }
    }

    private fun compact() {
        val currentSize = size
        buffer.copyInto(buffer, destinationOffset = 0, startIndex = start, endIndex = end)
        start = 0
        end = currentSize
    }
}

class ByteReader(private val bytes: ByteArray) {
    private var offset = 0

    fun readInt32(): Int? {
        if (remaining() < 4) return null
        val value = ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
        offset += 4
        return value
    }

    fun readScString(): String? {
        val length = readInt32() ?: return null
        if (length == -1) return null
        if (length < 0 || remaining() < length) return null
        val value = bytes.copyOfRange(offset, offset + length).decodeToString()
        offset += length
        return value
    }

    fun readBoolean(): Boolean? {
        if (remaining() < 1) return null
        return bytes[offset++].toInt() != 0
    }

    fun readByteString(): ByteArray? {
        val length = readInt32() ?: return null
        if (length == -1) return null
        if (length < 0 || remaining() < length) return null
        val value = bytes.copyOfRange(offset, offset + length)
        offset += length
        return value
    }

    fun currentOffset(): Int = offset

    private fun remaining(): Int = bytes.size - offset
}

class ByteWriter {
    private val output = ByteArrayOutputStream()

    fun writeInt(value: Int) {
        output.write((value ushr 24) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    fun writeString(value: String?) {
        if (value == null) {
            writeInt(-1)
            return
        }
        val bytes = value.encodeToByteArray()
        writeInt(bytes.size)
        output.write(bytes)
    }

    fun writeBytes(bytes: ByteArray) {
        output.write(bytes)
    }

    fun toByteArray(): ByteArray = output.toByteArray()
}

fun readUInt16(bytes: ByteArray, offset: Int): Int {
    return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}

fun readUInt24(bytes: ByteArray, offset: Int): Int {
    return ((bytes[offset].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        (bytes[offset + 2].toInt() and 0xFF)
}

fun buildSupercellMessage(messageId: Int, body: ByteArray, version: Int): ByteArray {
    val out = ByteArray(SupercellConstants.HEADER_SIZE + body.size)
    out[0] = ((messageId ushr 8) and 0xFF).toByte()
    out[1] = (messageId and 0xFF).toByte()
    out[2] = ((body.size ushr 16) and 0xFF).toByte()
    out[3] = ((body.size ushr 8) and 0xFF).toByte()
    out[4] = (body.size and 0xFF).toByte()
    out[5] = ((version ushr 8) and 0xFF).toByte()
    out[6] = (version and 0xFF).toByte()
    body.copyInto(out, SupercellConstants.HEADER_SIZE)
    return out
}
