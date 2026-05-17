package lilmuff1.bsml.protocol

const val SUPERCELL_HEADER_SIZE = 7

fun buildSupercellMessage(messageId: Int, body: ByteArray, version: Int): ByteArray {
    val out = ByteArray(SUPERCELL_HEADER_SIZE + body.size)
    out[0] = ((messageId ushr 8) and 0xFF).toByte()
    out[1] = (messageId and 0xFF).toByte()
    out[2] = ((body.size ushr 16) and 0xFF).toByte()
    out[3] = ((body.size ushr 8) and 0xFF).toByte()
    out[4] = (body.size and 0xFF).toByte()
    out[5] = ((version ushr 8) and 0xFF).toByte()
    out[6] = (version and 0xFF).toByte()
    body.copyInto(out, SUPERCELL_HEADER_SIZE)
    return out
}

fun readUInt16(bytes: ByteArray, offset: Int): Int {
    return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}

fun readUInt24(bytes: ByteArray, offset: Int): Int {
    return ((bytes[offset].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        (bytes[offset + 2].toInt() and 0xFF)
}
