package lilmuff1.bsml.protocol

import lilmuff1.bsml.asset.rewriteAssetUrl

object SupercellMessageRewriter {
    fun rewriteLoginFailedAssets(body: ByteArray): ByteArray {
        val parsed = LoginFailedPrefix.parse(body) ?: return body
        val rewrittenTail = LoginFailedTail.parse(parsed.suffix)?.let { tail ->
            val rewrittenUrls = tail.contentDownloadUrls
                .map(::rewriteAssetUrl)
                .let { urls -> if (urls.isEmpty()) urls else listOf(urls.first()) }
            tail.copy(contentDownloadUrls = rewrittenUrls).encode()
        } ?: parsed.suffix

        return parsed.copy(
            contentDownloadUrl = parsed.contentDownloadUrl?.let(::rewriteAssetUrl),
            suffix = rewrittenTail
        ).encode()
    }
}

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
    val hasCompressedFingerprint: Boolean,
    val compressedFingerprintLength: Int?,
    val rawPrefix: ByteArray,
    val contentDownloadUrls: List<String>,
    val rawSuffix: ByteArray
) {
    fun encode(): ByteArray {
        val writer = ByteWriter()
        writer.writeBytes(rawPrefix)
        writer.writeInt(contentDownloadUrls.size)
        contentDownloadUrls.forEach(writer::writeString)
        writer.writeBytes(rawSuffix)
        return writer.toByteArray()
    }

    companion object {
        fun parse(suffix: ByteArray): LoginFailedTail? {
            val reader = ByteReader(suffix)
            val hasByteString = reader.readBoolean() ?: return null
            val compressedFingerprintLength = if (hasByteString) {
                reader.readByteString()?.size ?: 0
            } else {
                reader.readScString()
                null
            }

            val countOffset = reader.currentOffset()
            val contentDownloadUrlsCount = reader.readInt32() ?: return null
            if (contentDownloadUrlsCount < 0 || contentDownloadUrlsCount > 128) return null

            val contentDownloadUrls = ArrayList<String>(contentDownloadUrlsCount)
            repeat(contentDownloadUrlsCount) {
                val url = reader.readScString() ?: return null
                contentDownloadUrls += url
            }

            return LoginFailedTail(
                hasCompressedFingerprint = hasByteString,
                compressedFingerprintLength = compressedFingerprintLength,
                rawPrefix = suffix.copyOfRange(0, countOffset),
                contentDownloadUrls = contentDownloadUrls,
                rawSuffix = suffix.copyOfRange(reader.currentOffset(), suffix.size)
            )
        }
    }
}
