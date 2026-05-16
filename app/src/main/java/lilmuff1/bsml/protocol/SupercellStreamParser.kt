package lilmuff1.bsml.protocol

import lilmuff1.bsml.state.VpnLogRepository

class SupercellStreamParser(
    private val onEncryptedStageReached: () -> Unit,
    private val localhostAssetBaseUrl: String
) {
    companion object {
        private const val CLIENT_HELLO = 10100
        private const val LOGIN = 10101
        private const val SERVER_HELLO = 20100
        private const val LOGIN_FAILED = 0x4E87
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
        while (queue.size >= SUPERCELL_HEADER_SIZE) {
            val header = queue.peek(SUPERCELL_HEADER_SIZE)
            val messageId = readUInt16(header, 0)
            val payloadLength = readUInt24(header, 2)
            val fullLength = SUPERCELL_HEADER_SIZE + payloadLength
            if (queue.size < fullLength) {
                debugLog("SC $direction waiting id=$messageId need=$fullLength have=${queue.size}")
                return
            }

            queue.skip(SUPERCELL_HEADER_SIZE)
            queue.read(payloadLength)
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
