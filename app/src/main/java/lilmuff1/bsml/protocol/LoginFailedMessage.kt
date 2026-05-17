package lilmuff1.bsml.protocol

data class LoginFailedPrefix(
    val reason: Int,
    val fingerprint: String?,
    val unknownString: String?,
    val contentDownloadUrl: String?,
    val updateUrl: String?,
    val reasonText: String?,
    val maintenanceWaitSecs: Int,
    val suffix: ByteArray
) {
    fun encode(): ByteArray {
        val writer = ByteWriter()
        writer.writeInt(reason)
        writer.writeString(fingerprint)
        writer.writeString(unknownString)
        writer.writeString(contentDownloadUrl)
        writer.writeString(updateUrl)
        writer.writeString(reasonText)
        writer.writeInt(maintenanceWaitSecs)
        return writer.toByteArray() + suffix
    }

    companion object {
        fun parse(body: ByteArray): LoginFailedPrefix? {
            val reader = ByteReader(body)
            val reason = reader.readInt32() ?: return null
            val fingerprint = reader.readScString()
            val unknownString = reader.readScString()
            val contentDownloadUrl = reader.readScString()
            val updateUrl = reader.readScString()
            val reasonText = reader.readScString()
            val maintenanceWaitSecs = reader.readInt32() ?: return null
            val suffix = body.copyOfRange(reader.currentOffset(), body.size)

            return LoginFailedPrefix(
                reason = reason,
                fingerprint = fingerprint,
                unknownString = unknownString,
                contentDownloadUrl = contentDownloadUrl,
                updateUrl = updateUrl,
                reasonText = reasonText,
                maintenanceWaitSecs = maintenanceWaitSecs,
                suffix = suffix
            )
        }
    }
}

data class LoginFailedTail(
    val unknownBoolean: Boolean,
    val compressedFingerprint: ByteArray?,
    val rawPrefix: ByteArray,
    val contentDownloadUrls: List<String>,
    val rawSuffix: ByteArray,
    val preserveRawPrefixWhenCompressedNull: Boolean = true
) {
    fun encode(): ByteArray {
        val writer = ByteWriter()
        if (compressedFingerprint != null) {
            writer.writeBoolean(unknownBoolean)
            writer.writeByteString(compressedFingerprint)
        } else if (preserveRawPrefixWhenCompressedNull) {
            writer.writeBytes(rawPrefix)
        } else {
            writer.writeBoolean(unknownBoolean)
            writer.writeString(null)
        }
        writer.writeInt(contentDownloadUrls.size)
        contentDownloadUrls.forEach(writer::writeString)
        writer.writeBytes(rawSuffix)
        return writer.toByteArray()
    }

    companion object {
        fun parse(suffix: ByteArray): LoginFailedTail? {
            val reader = ByteReader(suffix)
            val unknownBoolean = reader.readBoolean() ?: return null
            val compressedFingerprint = reader.readByteString()

            val countOffset = reader.currentOffset()
            val contentDownloadUrlsCount = reader.readInt32() ?: return null
            if (contentDownloadUrlsCount < 0 || contentDownloadUrlsCount > 128) return null

            val contentDownloadUrls = ArrayList<String>(contentDownloadUrlsCount)
            repeat(contentDownloadUrlsCount) {
                val url = reader.readScString() ?: return null
                contentDownloadUrls += url
            }

            return LoginFailedTail(
                unknownBoolean = unknownBoolean,
                compressedFingerprint = compressedFingerprint,
                rawPrefix = suffix.copyOfRange(0, countOffset),
                contentDownloadUrls = contentDownloadUrls,
                rawSuffix = suffix.copyOfRange(reader.currentOffset(), suffix.size)
            )
        }
    }
}
