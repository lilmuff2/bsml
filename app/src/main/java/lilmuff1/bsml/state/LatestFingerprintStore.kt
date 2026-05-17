package lilmuff1.bsml.state

import java.io.File

object LatestFingerprintStore {
    fun saveLatest(
        filesDir: File,
        fingerprintJson: String,
        origins: Collection<String>,
        clientHelloHash: String
    ) {
        LatestFingerprintRepository.saveLatest(
            filesDir = filesDir,
            fingerprintJson = fingerprintJson,
            origins = origins,
            clientHelloHash = clientHelloHash
        )
    }

    fun readStoredClientHelloHash(filesDir: File): String? {
        return LatestFingerprintRepository.readStoredClientHelloHash(filesDir)
    }

    fun storeObservedGameServer(filesDir: File, host: String) {
        LatestFingerprintRepository.storeObservedGameServer(filesDir, host)
    }
}
