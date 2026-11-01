package com.example.chat

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Запись голосового сообщения в AAC (.m4a). */
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun start() {
        val f = File(ctx.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(64_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release(); f.delete()
            throw e
        }
        rec = r; file = f; startedAt = SystemClock.elapsedRealtime()
    }

    /** Возвращает файл и длительность в секундах, либо null, если запись слишком короткая. */
    fun stop(): Pair<File, Double>? {
        val r = rec ?: return null
        val f = file
        rec = null; file = null
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        return try {
            r.stop(); r.release()
            if (seconds < 0.6) { f?.delete(); null } else f?.let { it to seconds }
        } catch (e: RuntimeException) {   // stop() падает, если записано меньше ~1 кадра
            r.release(); f?.delete(); null
        }
    }

    fun cancel() {
        val r = rec ?: return
        rec = null
        runCatching { r.stop() }
        r.release()
        file?.delete(); file = null
    }
}

/** Воспроизведение голосовых: одновременно играет только одно. */
object VoicePlayer {
    val playingId = MutableStateFlow<Long?>(null)
    val positionMs = MutableStateFlow(0)
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            player?.let {
                positionMs.value = it.currentPosition
                handler.postDelayed(this, 200)
            }
        }
    }

    fun toggle(id: Long, file: File) {
        if (playingId.value == id) { stop(); return }
        stop()
        val p = MediaPlayer()
        try {
            p.setDataSource(file.path)
            p.setOnCompletionListener { stop() }
            p.setOnErrorListener { _, _, _ -> stop(); true }
            p.prepare()
        } catch (e: Exception) {
            p.release()
            throw e
        }
        player = p
        playingId.value = id
        p.start()
        handler.post(ticker)
    }

    fun stop() {
        handler.removeCallbacks(ticker)
        player?.release()
        player = null
        playingId.value = null
        positionMs.value = 0
    }
}

/**
 * Кэш расшифрованных медиафайлов. На сервере файлы лежат только зашифрованными: скачиваем,
 * расшифровываем ключом из сообщения (блоки по 1 МиБ) и храним в приватной папке приложения.
 * Кэш очищается при выходе из аккаунта.
 */
object MediaCache {
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun ext(mime: String) = when {
        mime.startsWith("image/jpeg") -> "jpg"
        mime.startsWith("image/png") -> "png"
        mime.startsWith("image/gif") -> "gif"
        mime.startsWith("audio/mp4") -> "m4a"
        mime.startsWith("audio/webm") || mime.startsWith("video/webm") -> "webm"
        mime.startsWith("video/mp4") -> "mp4"
        else -> "bin"
    }

    suspend fun file(ctx: Context, api: ApiClient, token: String, e2ee: E2ee, m: ChatMessage): File {
        val path = m.mediaPath ?: throw ApiException("Нет файла")
        val info = m.file ?: throw ApiException("Нет ключа файла")
        val dir = File(ctx.cacheDir, "media").apply { mkdirs() }
        val name = path.substringAfterLast('/').removeSuffix(".bin")
        val plain = File(dir, "$name.${ext(info.mime)}")
        if (plain.exists()) return plain
        return locks.getOrPut(name) { Mutex() }.withLock {
            if (plain.exists()) return@withLock plain
            val enc = File(dir, "$name.enc")
            val tmp = File(dir, "$name.tmp")
            try {
                api.download(token, path, enc)
                withContext(Dispatchers.IO) {
                    e2ee.decryptFile(enc, tmp, info.key, info.nonce)
                    if (!tmp.renameTo(plain)) throw ApiException("Не удалось сохранить файл")
                }
            } finally {
                enc.delete(); tmp.delete()
            }
            plain
        }
    }

    fun clear(ctx: Context) {
        File(ctx.cacheDir, "media").deleteRecursively()
    }
}

fun mediaDuration(file: File): Double? {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(file.path)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { it / 1000.0 }
    } catch (e: Exception) {
        null
    } finally {
        r.release()
    }
}
