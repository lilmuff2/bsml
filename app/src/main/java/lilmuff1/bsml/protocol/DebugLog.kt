package lilmuff1.bsml.protocol

import lilmuff1.bsml.state.VpnLogRepository

const val ENABLE_DEBUG_LOGS = false

fun debugLog(message: String) {
    if (ENABLE_DEBUG_LOGS) {
        VpnLogRepository.log(message)
    }
}
