package lilmuff1.bsml.protocol

import java.io.ByteArrayOutputStream

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
        return readScStringBytes()?.decodeToString()
    }

    fun readScStringBytes(): ByteArray? {
        val length = readInt32() ?: return null
        if (length == -1) return null
        if (length < 0 || remaining() < length) return null
        val value = bytes.copyOfRange(offset, offset + length)
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

    fun readVInt(): Int? {
        var result = 0
        var shift = 0
        repeat(5) {
            if (remaining() < 1) return null
            val byte = bytes[offset++].toInt() and 0xFF
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) {
                return result
            }
            shift += 7
        }
        return null
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

    fun writeBoolean(value: Boolean) {
        output.write(if (value) 1 else 0)
    }

    fun writeByteString(value: ByteArray?) {
        if (value == null) {
            writeInt(-1)
            return
        }
        writeInt(value.size)
        output.write(value)
    }

    fun writeVInt(value: Int) {
        var current = value
        while (true) {
            val bits = current and 0x7F
            current = current ushr 7
            if (current == 0) {
                output.write(bits)
                return
            }
            output.write(bits or 0x80)
        }
    }

    fun writeBytes(bytes: ByteArray) {
        output.write(bytes)
    }

    fun toByteArray(): ByteArray = output.toByteArray()
}
