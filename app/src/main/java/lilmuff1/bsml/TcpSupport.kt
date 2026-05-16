package lilmuff1.bsml

object TcpPacketBuilder {
    fun build(
        sourceIp: Int,
        destinationIp: Int,
        sourcePort: Int,
        destinationPort: Int,
        sequenceNumber: Long,
        acknowledgmentNumber: Long,
        flags: Int,
        windowSize: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLength = 20
        val tcpHeaderLength = 20
        val totalLength = ipHeaderLength + tcpHeaderLength + payload.size
        val packet = ByteArray(totalLength)

        packet[0] = 0x45
        packet[1] = 0
        writeUInt16(packet, 2, totalLength)
        writeUInt16(packet, 4, 0)
        writeUInt16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = 6
        writeInt(packet, 12, sourceIp)
        writeInt(packet, 16, destinationIp)

        writeUInt16(packet, 20, sourcePort)
        writeUInt16(packet, 22, destinationPort)
        writeUInt32(packet, 24, sequenceNumber)
        writeUInt32(packet, 28, acknowledgmentNumber)
        packet[32] = (tcpHeaderLength / 4 shl 4).toByte()
        packet[33] = flags.toByte()
        writeUInt16(packet, 34, windowSize.coerceIn(0, 65535))
        writeUInt16(packet, 36, 0)
        writeUInt16(packet, 38, 0)

        payload.copyInto(packet, 40)

        writeUInt16(packet, 10, checksum(packet, 0, ipHeaderLength))
        writeUInt16(packet, 36, tcpChecksum(packet, sourceIp, destinationIp, tcpHeaderLength + payload.size))
        return packet
    }

    private fun writeUInt16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeUInt32(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = ((value ushr 24) and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeInt(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 24) and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 3] = (value and 0xFF).toByte()
    }

    private fun checksum(buffer: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        while (index < offset + length) {
            sum += (((buffer[index].toInt() and 0xFF) shl 8) or (buffer[index + 1].toInt() and 0xFF)).toLong()
            while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum ushr 16)
            index += 2
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun tcpChecksum(packet: ByteArray, sourceIp: Int, destinationIp: Int, tcpLength: Int): Int {
        var sum = 0L
        sum += ((sourceIp ushr 16) and 0xFFFF).toLong()
        sum += (sourceIp and 0xFFFF).toLong()
        sum += ((destinationIp ushr 16) and 0xFFFF).toLong()
        sum += (destinationIp and 0xFFFF).toLong()
        sum += 6
        sum += tcpLength

        var offset = 20
        var remaining = tcpLength
        while (remaining > 1) {
            sum += (((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)).toLong()
            while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum ushr 16)
            offset += 2
            remaining -= 2
        }
        if (remaining == 1) {
            sum += ((packet[offset].toInt() and 0xFF) shl 8).toLong()
        }
        while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
