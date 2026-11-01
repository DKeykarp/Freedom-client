package com.example.chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Скачивает и кэширует на диске картинки аватаров — по тому же принципу, что и стикеры, но без ViewModel
 * в цепочке вызовов: Avatar() используется в добром десятке мест по всему приложению, многие из которых
 * не имеют ViewModel под рукой, а токен для запроса (Authorization) нужен всё равно. Токен выставляется
 * один раз при входе (см. ChatViewModel.session) и живёт здесь глобально, как и сам кэш файлов.
 */
object AvatarCache {
    @Volatile var token: String? = null
    private val client = OkHttpClient()
    private val cache = mutableMapOf<String, File>()

    /** [path] — относительный путь с сервера (например, "/avatars/xyz.jpg"), как отдаёт API. */
    suspend fun file(context: Context, path: String): File? {
        cache[path]?.let { if (it.exists()) return it }
        val t = token ?: return null
        return try {
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url(SERVER_URL + path).header("Authorization", "Bearer $t").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val f = File(context.cacheDir, "avatar_${path.hashCode()}")
                    resp.body?.byteStream()?.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
                        ?: return@withContext null
                    f
                }
            }?.also { cache[path] = it }
        } catch (e: Exception) {
            null
        }
    }

    /** Звать при замене/удалении своего аватара — иначе старая картинка провисит в кэше до перезапуска. */
    fun invalidate(path: String?) {
        path?.let { cache.remove(it) }
    }
}
