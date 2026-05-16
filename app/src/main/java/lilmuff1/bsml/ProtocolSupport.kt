package lilmuff1.bsml

import java.io.ByteArrayOutputStream
import kotlin.math.min

fun rewriteAssetUrl(url: String): String {
    return when {
        url.startsWith(AssetProxyService.PRIMARY_ASSET_BASE_ORIGIN) ->
            url.replace(AssetProxyService.PRIMARY_ASSET_BASE_ORIGIN, AssetProxyService.PRIMARY_LOCAL_BASE_URL)
        url.startsWith(AssetProxyService.SECONDARY_ASSET_BASE_ORIGIN) ->
            url.replace(AssetProxyService.SECONDARY_ASSET_BASE_ORIGIN, AssetProxyService.PRIMARY_LOCAL_BASE_URL)
        else -> url
    }
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

object PacketParser {
    private const val IPV4_MIN_HEADER_LENGTH = 20
    private const val PROTOCOL_TCP = 6

    fun parse(packet: ByteArray, length: Int, targetIpInts: Set<Int>): PacketEvent? {
        if (length < IPV4_MIN_HEADER_LENGTH) return null
        if (packet[0].toInt().ushr(4) != 4) return null

        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        if (length < ipHeaderLength + 20) return null
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != PROTOCOL_TCP) return null

        val sourceIp = readIpv4(packet, 12)
        val destinationIp = readIpv4(packet, 16)
        if (destinationIp !in targetIpInts && sourceIp !in targetIpInts) return null

        val sourcePort = readUnsignedShort(packet, ipHeaderLength, length)
        val destinationPort = readUnsignedShort(packet, ipHeaderLength + 2, length)
        if (sourcePort < 0 || destinationPort < 0) return null

        val sequenceNumber = readUnsignedInt(packet, ipHeaderLength + 4, length) ?: return null
        val acknowledgmentNumber = readUnsignedInt(packet, ipHeaderLength + 8, length) ?: return null
        val tcpHeaderLength = ((packet[ipHeaderLength + 12].toInt() ushr 4) and 0x0F) * 4
        if (tcpHeaderLength < 20 || length < ipHeaderLength + tcpHeaderLength) return null

        val flags = packet[ipHeaderLength + 13].toInt() and 0xFF
        val windowSize = readUnsignedShort(packet, ipHeaderLength + 14, length).coerceAtLeast(0)
        val payloadOffset = ipHeaderLength + tcpHeaderLength
        val payloadLength = (length - payloadOffset).coerceAtLeast(0)

        return PacketEvent(
            protocol = "TCP",
            sourceIp = intToIpv4(sourceIp),
            sourcePort = sourcePort,
            destinationIp = intToIpv4(destinationIp),
            destinationPort = destinationPort,
            sourceIpInt = sourceIp,
            destinationIpInt = destinationIp,
            payloadOffset = payloadOffset,
            payloadLength = payloadLength,
            packet = packet.copyOf(length),
            sequenceNumber = sequenceNumber,
            ackNumber = acknowledgmentNumber,
            tcpFlags = flags,
            windowSize = windowSize,
            summary = "${intToIpv4(sourceIp)}:$sourcePort -> ${intToIpv4(destinationIp)}:$destinationPort [${tcpFlagsToString(flags)}]"
        )
    }

    private fun readUnsignedShort(packet: ByteArray, offset: Int, length: Int): Int {
        if (offset + 1 >= length) return -1
        return ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
    }

    private fun readUnsignedInt(packet: ByteArray, offset: Int, length: Int): Long? {
        if (offset + 3 >= length) return null
        return ((packet[offset].toLong() and 0xFF) shl 24) or
            ((packet[offset + 1].toLong() and 0xFF) shl 16) or
            ((packet[offset + 2].toLong() and 0xFF) shl 8) or
            (packet[offset + 3].toLong() and 0xFF)
    }

    private fun readIpv4(packet: ByteArray, offset: Int): Int {
        return ((packet[offset].toInt() and 0xFF) shl 24) or
            ((packet[offset + 1].toInt() and 0xFF) shl 16) or
            ((packet[offset + 2].toInt() and 0xFF) shl 8) or
            (packet[offset + 3].toInt() and 0xFF)
    }
}

data class PacketEvent(
    val protocol: String,
    val sourceIp: String,
    val sourcePort: Int,
    val destinationIp: String,
    val destinationPort: Int,
    val sourceIpInt: Int,
    val destinationIpInt: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
    val packet: ByteArray,
    val sequenceNumber: Long,
    val ackNumber: Long,
    val tcpFlags: Int,
    val windowSize: Int,
    val summary: String
) {
    fun payload(): ByteArray {
        if (payloadLength <= 0) return byteArrayOf()
        return packet.copyOfRange(payloadOffset, payloadOffset + payloadLength)
    }
}

data class SessionKey(
    val clientIp: Int,
    val serverIp: Int,
    val clientPort: Int,
    val serverPort: Int
) {
    companion object {
        fun fromEvent(event: PacketEvent): SessionKey {
            return SessionKey(
                clientIp = event.sourceIpInt,
                serverIp = event.destinationIpInt,
                clientPort = event.sourcePort,
                serverPort = event.destinationPort
            )
        }
    }
}

class SupercellStreamParser(
    private val onEncryptedStageReached: () -> Unit,
    private val localhostAssetBaseUrl: String
) {
    companion object {
        private const val CLIENT_HELLO = 10100
        private const val LOGIN = 10101
        private const val SERVER_HELLO = 20100
        private const val LOGIN_FAILED = 20103
    }

    private val clientBuffer = ByteQueue()
    private val serverBuffer = ByteQueue()

    fun onClientBytes(bytes: ByteArray) {
        safelyParse("CLIENT->SERVER", clientBuffer, bytes)
    }

    fun onServerBytes(bytes: ByteArray) {
        safelyParse("SERVER->CLIENT", serverBuffer, bytes)
    }

    private fun safelyParse(direction: String, queue: ByteQueue, bytes: ByteArray) {
        try {
            appendAndParse(direction, queue, bytes)
        } catch (error: Throwable) {
            VpnLogRepository.log(
                "SC $direction parserError=${error::class.java.simpleName}: ${error.message ?: "unknown"}"
            )
        }
    }

    private fun appendAndParse(direction: String, queue: ByteQueue, bytes: ByteArray) {
        queue.append(bytes)
        while (queue.size >= 7) {
            val header = queue.peek(7)
            val messageId = readUInt16(header, 0)
            val payloadLength = readUInt24(header, 2)
            val fullLength = 7 + payloadLength
            if (queue.size < fullLength) {
                debugLog("SC $direction waiting id=$messageId need=$fullLength have=${queue.size}")
                return
            }

            queue.skip(7)
            val body = queue.read(payloadLength)
            when (messageId) {
                CLIENT_HELLO -> {}
                LOGIN -> {}
                SERVER_HELLO -> {}
                LOGIN_FAILED -> onEncryptedStageReached()
                else -> {}
            }
        }
    }
}

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

    fun currentOffset(): Int = offset

    private fun remaining(): Int = bytes.size - offset
}

data class LoginFailedPrefix(
    val reason: Int,
    val fingerprint: String?,
    val unknownString: String?,
    val contentDownloadUrl: String?,
    val updateUrl: String?,
    val reasonText: String?,
    val maintenanceWaitSecs: Int,
    val suffix: ByteArray
) {
    fun encode(): ByteArray {
        val writer = ByteWriter()
        writer.writeInt(reason)
        writer.writeString(fingerprint)
        writer.writeString(unknownString)
        writer.writeString(contentDownloadUrl)
        writer.writeString(updateUrl)
        writer.writeString(reasonText)
        writer.writeInt(maintenanceWaitSecs)
        return writer.toByteArray() + suffix
    }

    companion object {
        fun parse(body: ByteArray): LoginFailedPrefix? {
            val reader = ByteReader(body)
            val reason = reader.readInt32() ?: return null
            val fingerprint = reader.readScString()
            val unknownString = reader.readScString()
            val contentDownloadUrl = reader.readScString()
            val updateUrl = reader.readScString()
            val reasonText = reader.readScString()
            val maintenanceWaitSecs = reader.readInt32() ?: return null
            val suffix = body.copyOfRange(reader.currentOffset(), body.size)

            return LoginFailedPrefix(
                reason = reason,
                fingerprint = fingerprint,
                unknownString = unknownString,
                contentDownloadUrl = contentDownloadUrl,
                updateUrl = updateUrl,
                reasonText = reasonText,
                maintenanceWaitSecs = maintenanceWaitSecs,
                suffix = suffix
            )
        }
    }
}

data class LoginFailedTail(
    val unknownBoolean: Boolean,
    val compressedFingerprint: ByteArray?,
    val rawPrefix: ByteArray,
    val contentDownloadUrls: List<String>,
    val rawSuffix: ByteArray,
    val preserveRawPrefixWhenCompressedNull: Boolean = true
) {
    fun encode(): ByteArray {
        val writer = ByteWriter()
        if (compressedFingerprint != null) {
            writer.writeBoolean(unknownBoolean)
            writer.writeByteString(compressedFingerprint)
        } else if (preserveRawPrefixWhenCompressedNull) {
            writer.writeBytes(rawPrefix)
        } else {
            writer.writeBoolean(unknownBoolean)
            writer.writeString(null)
        }
        writer.writeInt(contentDownloadUrls.size)
        contentDownloadUrls.forEach(writer::writeString)
        writer.writeBytes(rawSuffix)
        return writer.toByteArray()
    }

    companion object {
        fun parse(suffix: ByteArray): LoginFailedTail? {
            val reader = ByteReader(suffix)
            val unknownBoolean = reader.readBoolean() ?: return null
            val compressedFingerprint = reader.readByteString()

            val countOffset = reader.currentOffset()
            val contentDownloadUrlsCount = reader.readInt32() ?: return null
            if (contentDownloadUrlsCount < 0 || contentDownloadUrlsCount > 128) return null

            val contentDownloadUrls = ArrayList<String>(contentDownloadUrlsCount)
            repeat(contentDownloadUrlsCount) {
                val url = reader.readScString() ?: return null
                contentDownloadUrls += url
            }

            return LoginFailedTail(
                unknownBoolean = unknownBoolean,
                compressedFingerprint = compressedFingerprint,
                rawPrefix = suffix.copyOfRange(0, countOffset),
                contentDownloadUrls = contentDownloadUrls,
                rawSuffix = suffix.copyOfRange(reader.currentOffset(), suffix.size)
            )
        }
    }
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

    fun writeBytes(bytes: ByteArray) {
        output.write(bytes)
    }

    fun toByteArray(): ByteArray = output.toByteArray()
}

const val TCP_FIN = 0x01
const val TCP_SYN = 0x02
const val TCP_RST = 0x04
const val TCP_PSH = 0x08
const val TCP_ACK = 0x10
const val SUPERCELL_HEADER_SIZE = 7
const val ENABLE_DEBUG_LOGS = false

fun debugLog(message: String) {
    if (ENABLE_DEBUG_LOGS) {
        VpnLogRepository.log(message)
    }
}

fun tcpFlagsToString(flags: Int): String {
    val names = ArrayList<String>(5)
    if (flags and TCP_SYN != 0) names += "SYN"
    if (flags and TCP_ACK != 0) names += "ACK"
    if (flags and TCP_FIN != 0) names += "FIN"
    if (flags and TCP_RST != 0) names += "RST"
    if (flags and TCP_PSH != 0) names += "PSH"
    return names.joinToString("|")
}

fun ipv4BytesToInt(address: ByteArray): Int {
    return ((address[0].toInt() and 0xFF) shl 24) or
        ((address[1].toInt() and 0xFF) shl 16) or
        ((address[2].toInt() and 0xFF) shl 8) or
        (address[3].toInt() and 0xFF)
}

fun intToIpv4(address: Int): String {
    return listOf(
        address.ushr(24) and 0xFF,
        address.ushr(16) and 0xFF,
        address.ushr(8) and 0xFF,
        address and 0xFF
    ).joinToString(".")
}

fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }
}

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
