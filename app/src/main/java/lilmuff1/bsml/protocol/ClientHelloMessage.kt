package lilmuff1.bsml.protocol

data class ClientHelloVersion(
    val major: Int,
    val revision: Int,
    val build: Int
) {
    val displayName: String
        get() = "$major.$revision.$build"
}

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

fun readClientHelloVersion(body: ByteArray): ClientHelloVersion? {
    val major = readInt32(body, 8) ?: return null
    val revision = readInt32(body, 12) ?: return null
    val build = readInt32(body, 16) ?: return null
    return ClientHelloVersion(
        major = major,
        revision = revision,
        build = build
    )
}

fun rewriteClientHello(
    body: ByteArray,
    forcedContentHash: String?,
    zeroGameVersion: Boolean
): ByteArray? {
    val contentHashLengthOffset = 20
    if (body.size < contentHashLengthOffset + 4) return null

    val protocolVersion = readInt32(body, 0) ?: return null
    val keyVersion = readInt32(body, 4) ?: return null
    val major = if (zeroGameVersion) 0 else readInt32(body, 8) ?: return null
    val revision = if (zeroGameVersion) 0 else readInt32(body, 12) ?: return null
    val build = if (zeroGameVersion) 0 else readInt32(body, 16) ?: return null

    val length = readInt32(body, contentHashLengthOffset) ?: return null
    val suffixOffset = when {
        length == -1 -> contentHashLengthOffset + 4
        length >= 0 && body.size >= contentHashLengthOffset + 4 + length ->
            contentHashLengthOffset + 4 + length
        else -> return null
    }

    val currentContentHash = if (length == -1) null else {
        body.copyOfRange(contentHashLengthOffset + 4, suffixOffset).decodeToString()
    }

    val writer = ByteWriter()
    writer.writeInt(protocolVersion)
    writer.writeInt(keyVersion)
    writer.writeInt(major)
    writer.writeInt(revision)
    writer.writeInt(build)
    writer.writeString(forcedContentHash ?: currentContentHash)
    writer.writeBytes(body.copyOfRange(suffixOffset, body.size))
    return writer.toByteArray()
}

private fun readInt32(body: ByteArray, offset: Int): Int? {
    if (body.size < offset + 4) return null
    return ((body[offset].toInt() and 0xFF) shl 24) or
        ((body[offset + 1].toInt() and 0xFF) shl 16) or
        ((body[offset + 2].toInt() and 0xFF) shl 8) or
        (body[offset + 3].toInt() and 0xFF)
}
