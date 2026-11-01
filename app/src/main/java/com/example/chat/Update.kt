package com.example.chat

/** mandatory — версия ниже той, что сервер ещё поддерживает; optional — просто вышла версия новее. */
enum class UpdateSeverity { MANDATORY, OPTIONAL }

data class UpdateInfo(val severity: UpdateSeverity, val current: String, val latest: String, val url: String?)

private val VERSION_RE = Regex("""^\d+(\.\d+){1,2}$""")
fun isValidVersion(v: String?): Boolean = v != null && VERSION_RE.matches(v)

/** Сравнивает версии вида "1.2.3" или "5.0" по числовым частям, а не по строкам: -1, 0 или 1. */
fun compareVersions(a: String, b: String): Int {
    val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
    val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrElse(i) { 0 }
        val y = pb.getOrElse(i) { 0 }
        if (x != y) return if (x < y) -1 else 1
    }
    return 0
}

/**
 * Решает, что показать пользователю, по ответу сервера (ping.minClientVersion, downloads) и текущей версии.
 * Битые/отсутствующие данные (например, для Android ещё нет установщика) не считаются ошибкой — тогда
 * просто нечего предложить.
 */
fun decideUpdate(currentVersion: String, minVersion: String?, downloads: List<DownloadItem>): UpdateInfo? {
    val item = downloads.firstOrNull { it.platform == "android" && isValidVersion(it.version) }

    if (isValidVersion(minVersion) && compareVersions(currentVersion, minVersion!!) < 0) {
        val qualifies = item != null && compareVersions(item.version!!, minVersion) >= 0
        return UpdateInfo(
            UpdateSeverity.MANDATORY, currentVersion,
            latest = if (qualifies) item!!.version!! else minVersion,
            url = if (qualifies) item!!.url else null,
        )
    }
    if (item != null && compareVersions(currentVersion, item.version!!) < 0) {
        return UpdateInfo(UpdateSeverity.OPTIONAL, currentVersion, item.version!!, item.url)
    }
    return null
}
