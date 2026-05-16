package lilmuff1.bsml.protocol

fun rewriteClientHelloContentHash(body: ByteArray, forcedContentHash: String): ByteArray? {
    val contentHashLengthOffset = 20
    if (body.size < contentHashLengthOffset + 4) return null

    val length = ((body[contentHashLengthOffset].toInt() and 0xFF) shl 24) or
        ((body[contentHashLengthOffset + 1].toInt() and 0xFF) shl 16) or
        ((body[contentHashLengthOffset + 2].toInt() and 0xFF) shl 8) or
        (body[contentHashLengthOffset + 3].toInt() and 0xFF)

    val suffixOffset = when {
        length == -1 -> contentHashLengthOffset + 4
        length >= 0 && body.size >= contentHashLengthOffset + 4 + length ->
            contentHashLengthOffset + 4 + length
        else -> return null
    }

    val writer = ByteWriter()
    writer.writeBytes(body.copyOfRange(0, contentHashLengthOffset))
    writer.writeString(forcedContentHash)
    writer.writeBytes(body.copyOfRange(suffixOffset, body.size))
    return writer.toByteArray()
}

fun readClientHelloContentHash(body: ByteArray): String? {
    val contentHashLengthOffset = 20
    if (body.size < contentHashLengthOffset + 4) return null

    val length = ((body[contentHashLengthOffset].toInt() and 0xFF) shl 24) or
        ((body[contentHashLengthOffset + 1].toInt() and 0xFF) shl 16) or
        ((body[contentHashLengthOffset + 2].toInt() and 0xFF) shl 8) or
        (body[contentHashLengthOffset + 3].toInt() and 0xFF)

    if (length == -1) return null
    if (length < 0 || body.size < contentHashLengthOffset + 4 + length) return null
    return body.copyOfRange(contentHashLengthOffset + 4, contentHashLengthOffset + 4 + length).decodeToString()
}
