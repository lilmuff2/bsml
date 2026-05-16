package lilmuff1.bsml.vpn

import lilmuff1.bsml.logging.VpnLogRepository

object VpnRelayConfig {
    const val TARGET_HOST = "game.brawlstarsgame.com"
    const val TARGET_PORT = 9339
    const val MTU = 32767
    const val TUN_ADDRESS = "10.10.10.2"
    const val SOCKET_CONNECT_TIMEOUT_MS = 10_000
    const val SOCKET_READ_TIMEOUT_MS = 15_000
    const val SOCKET_WRITE_TIMEOUT_MS = 5_000
    const val MAX_TCP_PAYLOAD = 1400
}

const val TCP_FIN = 0x01
const val TCP_SYN = 0x02
const val TCP_RST = 0x04
const val TCP_PSH = 0x08
const val TCP_ACK = 0x10
const val ENABLE_DEBUG_LOGS = false

fun debugLog(message: String) {
    if (ENABLE_DEBUG_LOGS) {
        VpnLogRepository.log(message)
    }
}

fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }
}
