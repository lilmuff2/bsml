package lilmuff1.bsml.vpn

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
