package lilmuff1.bsml.service

fun formatLoginFailedLog(
    result: LoginFailedRewriteResult,
    oldLength: Int,
    newLength: Int,
    version: Int
): String {
    val mode = result.patchStats.mode?.let { " mode=$it" } ?: ""
    val oldRootSha = result.patchStats.rootShaOld
    val newRootSha = result.patchStats.rootShaNew ?: result.hashes.rootSha
    return "SC LOGIN_FAILED$mode shaPatched=${result.patchStats.fileShaPatched} " +
        "rootSha=${oldRootSha ?: "<unknown>"}" +
        rootShaChangeSuffix(oldRootSha, newRootSha) +
        " len=${lengthChangeText(oldLength, newLength)} ver=$version"
}

private fun rootShaChangeSuffix(old: String?, new: String?): String {
    if (new == null) return ""
    if (old != null && old == new) return ""
    return "->$new"
}

private fun lengthChangeText(oldLength: Int, newLength: Int): String {
    return if (oldLength == newLength) oldLength.toString() else "$oldLength->$newLength"
}
