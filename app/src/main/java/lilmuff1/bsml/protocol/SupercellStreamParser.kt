package lilmuff1.bsml.protocol

import lilmuff1.bsml.logging.VpnLogRepository
import lilmuff1.bsml.vpn.debugLog

class SupercellStreamParser {
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
            appendAndParse(direction, queue)
            queue.append(bytes)
        } catch (error: Throwable) {
            VpnLogRepository.log(
                "SC $direction parserError=${error::class.java.simpleName}: ${error.message ?: "unknown"}"
            )
        }
        try {
            appendAndParse(direction, queue)
        } catch (error: Throwable) {
            VpnLogRepository.log(
                "SC $direction parserError=${error::class.java.simpleName}: ${error.message ?: "unknown"}"
            )
        }
    }

    private fun appendAndParse(direction: String, queue: ByteQueue) {
        while (queue.size >= SupercellConstants.HEADER_SIZE) {
            val header = queue.peek(SupercellConstants.HEADER_SIZE)
            val messageId = readUInt16(header, 0)
            val payloadLength = readUInt24(header, 2)
            val version = readUInt16(header, 5)
            val fullLength = SupercellConstants.HEADER_SIZE + payloadLength
            if (queue.size < fullLength) {
                debugLog("SC $direction waiting id=$messageId need=$fullLength have=${queue.size}")
                return
            }

            queue.skip(SupercellConstants.HEADER_SIZE)
            val body = queue.read(payloadLength)
            VpnLogRepository.log("SC $direction id=$messageId len=$payloadLength ver=$version")
            if (messageId == SupercellConstants.CLIENT_HELLO) {
                ClientHelloMessage.parse(body)?.let { VpnLogRepository.log(it.toLogString()) }
            }
        }
    }
}
