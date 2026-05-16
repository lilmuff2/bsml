package lilmuff1.bsml.vpn

object PacketParser {
    private const val IPV4_MIN_HEADER_LENGTH = 20
    private const val PROTOCOL_TCP = 6

    fun parse(packet: ByteArray, length: Int, targetIpInts: Set<Int>): PacketEvent? {
        if (length < IPV4_MIN_HEADER_LENGTH) return null
        if (packet[0].toInt().ushr(4) != 4) return null

        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        if (length < ipHeaderLength + 20) return null
        if ((packet[9].toInt() and 0xFF) != PROTOCOL_TCP) return null

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
            windowSize = windowSize
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
    val windowSize: Int
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

fun tcpFlagsToString(flags: Int): String {
    val names = ArrayList<String>(5)
    if (flags and TCP_SYN != 0) names += "SYN"
    if (flags and TCP_ACK != 0) names += "ACK"
    if (flags and TCP_FIN != 0) names += "FIN"
    if (flags and TCP_RST != 0) names += "RST"
    if (flags and TCP_PSH != 0) names += "PSH"
    return names.joinToString("|")
}
