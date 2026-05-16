package lilmuff1.bsml.protocol

data class ClientHelloMessage(
    val protocolVersion: Int,
    val keyVersion: Int,
    val major: Int,
    val revision: Int,
    val build: Int,
    val contentHash: String?,
    val deviceType: Int,
    val appStore: Int
) {
    fun toLogString(): String {
        return "SC 10100 protocolVersion=$protocolVersion keyVersion=$keyVersion " +
            "major=$major revision=$revision build=$build contentHash=${contentHash ?: "<null>"} " +
            "deviceType=$deviceType appStore=$appStore"
    }

    companion object {
        fun parse(body: ByteArray): ClientHelloMessage? {
            val reader = ByteReader(body)
            return ClientHelloMessage(
                protocolVersion = reader.readInt32() ?: return null,
                keyVersion = reader.readInt32() ?: return null,
                major = reader.readInt32() ?: return null,
                revision = reader.readInt32() ?: return null,
                build = reader.readInt32() ?: return null,
                contentHash = reader.readScString(),
                deviceType = reader.readInt32() ?: return null,
                appStore = reader.readInt32() ?: return null
            )
        }
    }
}
