package lilmuff1.bsml.vpn

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

const val TCP_FIN = 0x01
const val TCP_SYN = 0x02
const val TCP_RST = 0x04
const val TCP_PSH = 0x08
const val TCP_ACK = 0x10

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
