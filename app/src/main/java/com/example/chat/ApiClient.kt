package com.example.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Session(val token: String, val username: String)

/** Ответ /register и /login: сессия и зашифрованные приватные ключи (расшифровать может только владелец пароля). */
data class AuthResponse(val session: Session, val keyBlob: String)

/** Публичные ключи и блоб приватных ключей, которые клиент создаёт при регистрации. */
data class RegisterKeys(val dhPub: String, val sigPub: String, val keyBlob: String)

data class Room(
    val id: Long,
    val name: String,
    val isDirect: Boolean,
    val kind: String,            // "direct" | "group" | "channel"
    val role: String?,           // "owner" | "admin" | "member" | null (для личных чатов роль не имеет смысла)
    val unread: Int,
    val lastPreview: String?,   // расшифровывается на устройстве
    val sortTs: Double,
    val peer: String? = null,
    val epoch: Int = 0,         // актуальная эпоха ключа комнаты
    val last: RawMessage? = null,
    val autodeleteSeconds: Int? = null,   // самоудаление новых сообщений (null/0 — выключено)
    val avatar: String? = null,          // для личного чата — аватар собеседника; для группы/канала — свой нет
)

data class PublicRoom(val id: Long, val name: String, val kind: String, val members: Int)

/** Ответ /api/ping. minClientVersion — минимум для этой платформы (android), если сервер его задал. */
data class ServerInfo(val version: String, val minClientVersion: String?)

/** Один пункт из /api/downloads (может быть не для этой платформы — см. platform). */
data class DownloadItem(val name: String, val url: String, val platform: String, val version: String?)

/** Запись сообщения так, как её хранит сервер: шифртекст и подпись. */
data class RawMessage(
    val id: Long,
    val roomId: Long,
    val username: String,
    val kind: String,           // e2e | call
    val text: String,           // только для call (итог звонка)
    val epoch: Int,
    val ct: String?,
    val sig: String?,
    val mediaPath: String?,
    val ts: Double,
    val editedTs: Double? = null,
    val reactions: List<RawReaction> = emptyList(),
    val pinned: Boolean = false,
    val votes: List<RawVote> = emptyList(),
)

/** Реакция — как обычное сообщение: зашифрована ключом комнаты, сервер видит только кто/когда, не эмодзи. */
data class RawReaction(val username: String, val epoch: Int, val ct: String, val sig: String)
data class DecodedReaction(val username: String, val emoji: String)
data class RawVote(val username: String, val epoch: Int, val ct: String, val sig: String)
data class DecodedVote(val username: String, val option: Int)
data class ScheduledMsg(val id: Long, val roomId: Long, val sendAt: Double)
data class Sticker(val id: Long, val url: String, val position: Int)
data class StickerPack(
    val id: Long, val name: String, val creatorUsername: String, val stickerCount: Int,
    val created: Double, val installed: Boolean = false, val stickers: List<Sticker> = emptyList(),
)

/** Ключ и nonce файла (лежат внутри зашифрованного сообщения). */
data class FileInfo(val key: String, val nonce: String, val size: Long, val mime: String)

/** Снимок сообщения, на которое отвечают: сделан в момент ответа и не меняется, если оригинал отредактируют. */
data class ReplyRef(val id: Long, val username: String, val kind: String, val text: String)

/** Расшифрованное сообщение. kind: text | image | audio | video | file | call | locked (не удалось расшифровать). */
data class ChatMessage(
    val id: Long,
    val roomId: Long,
    val username: String,
    val text: String,
    val kind: String,
    val mediaPath: String?,
    val duration: Double?,
    val ts: Double,
    val file: FileInfo? = null,
    val fileName: String? = null,
    val replyTo: ReplyRef? = null,
    val editedTs: Double? = null,
    val reactions: List<RawReaction> = emptyList(),   // сырые (для повторной отправки без перешифровки списка)
    val reactionsDecoded: List<DecodedReaction> = emptyList(),
    val pinned: Boolean = false,
    val forwardedFrom: String? = null,
    val votes: List<RawVote> = emptyList(),
    val votesDecoded: List<DecodedVote> = emptyList(),
    val question: String? = null,       // заполнено только для kind == "poll" (см. ChatViewModel.openMsg)
    val options: List<String>? = null,
)

/** Ответ сервера на редактирование ({@code PUT .../messages/{id}}) и рассылка message_edited по WS. */
data class EditResult(val id: Long, val roomId: Long, val epoch: Int, val ct: String, val sig: String, val editedTs: Double)

/** Публичный профиль: имя и «о себе» — сервер отдаёт его любому, кто ищет по имени пользователя. */
data class Profile(val username: String, val bio: String, val avatar: String? = null)

data class Envelope(
    val roomId: Long, val epoch: Int, val sender: String,
    val eph: String, val n: String, val c: String, val sig: String,
)

data class KeysResponse(val latest: Map<Long, Int>, val envelopes: List<Envelope>)
data class Member(val username: String, val dhPub: String, val sigPub: String)

data class IceCfg(val urls: List<String>, val username: String?, val credential: String?)

class ApiException(message: String, val code: Int = 0) : Exception(message)

fun parseRaw(o: JSONObject) = RawMessage(
    id = o.getLong("id"),
    roomId = o.getLong("room_id"),
    username = o.getString("username"),
    kind = o.optString("kind", "e2e"),
    text = if (o.isNull("text")) "" else o.getString("text"),
    epoch = if (o.isNull("epoch")) 0 else o.getInt("epoch"),
    ct = if (o.isNull("ct")) null else o.getString("ct"),
    sig = if (o.isNull("sig")) null else o.getString("sig"),
    mediaPath = if (!o.has("media") || o.isNull("media")) null else o.getString("media"),
    ts = o.getDouble("ts"),
    editedTs = if (!o.has("edited_ts") || o.isNull("edited_ts")) null else o.getDouble("edited_ts"),
    reactions = parseReactions(o.optJSONArray("reactions")),
    pinned = o.optBoolean("pinned", false),
    votes = parseVotes(o.optJSONArray("votes")),
)

fun parseVotes(arr: JSONArray?): List<RawVote> {
    if (arr == null) return emptyList()
    return List(arr.length()) { i ->
        val v = arr.getJSONObject(i)
        RawVote(v.getString("username"), v.getInt("epoch"), v.getString("ct"), v.getString("sig"))
    }
}

fun parseReactions(arr: JSONArray?): List<RawReaction> {
    if (arr == null) return emptyList()
    return List(arr.length()) { i ->
        val r = arr.getJSONObject(i)
        RawReaction(r.getString("username"), r.getInt("epoch"), r.getString("ct"), r.getString("sig"))
    }
}

/** Превью для списка комнат из уже расшифрованного сообщения. */
fun previewOf(isDirect: Boolean, username: String, text: String, kind: String, fileName: String? = null): String {
    val body = when (kind) {
        "image" -> "📷 Фото"
        "audio" -> "🎤 Голосовое сообщение"
        "video" -> "🎥 Видеосообщение"
        "file" -> "📎 ${fileName ?: "Файл"}"
        "locked" -> "🔒 Сообщение"
        else -> text
    }
    return if (isDirect || kind == "call") body else "$username: $body"
}

/** Превью появится после расшифровки последнего сообщения (это делает ViewModel). */
fun parseRoom(o: JSONObject): Room {
    val isDirect = o.getBoolean("is_direct")
    return Room(
        id = o.getLong("id"),
        name = o.getString("name"),
        isDirect = isDirect,
        kind = o.optString("kind", if (isDirect) "direct" else "group"),
        role = if (o.has("role") && !o.isNull("role")) o.getString("role") else null,
        unread = o.getInt("unread"),
        lastPreview = null,
        sortTs = o.getDouble("sort_ts"),
        peer = if (o.isNull("peer")) null else o.getString("peer"),
        epoch = o.optInt("epoch", 0),
        last = o.optJSONObject("last")?.let { parseRaw(it) },
        autodeleteSeconds = if (o.isNull("autodelete_seconds")) null else o.getInt("autodelete_seconds"),
        avatar = if (o.has("avatar") && !o.isNull("avatar")) o.getString("avatar") else null,
    )
}

/** Единственный сервер. Приложение работает только с ним и только по HTTPS. */
const val SERVER_URL = "https://contora.is-local.org"

// Держите в паре с versionName в app/build.gradle.kts — по этой версии сервер решает, нужно ли
// показать уведомление об обновлении (main.py: /api/ping, /api/downloads; см. Update.kt).
const val APP_VERSION = "5.0"

class ApiClient(serverUrl: String = SERVER_URL) {
    private val base = serverUrl.trim().trimEnd('/')

    init {
        require(base.startsWith("https://")) { "Сервер доступен только по HTTPS" }
    }

    private val jsonType = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // держит WebSocket живым
        .build()

    private fun json(vararg p: Pair<String, Any>): RequestBody =
        JSONObject(mapOf(*p)).toString().toRequestBody(jsonType)

    private fun authed(token: String, path: String) =
        Request.Builder().url("$base$path").header("Authorization", "Bearer $token")

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** authSecret — не пароль пользователя, а секрет, выведенный из него на устройстве (см. E2EE.md). */
    suspend fun authenticate(
        register: Boolean, username: String, authSecret: String, keys: RegisterKeys?,
    ): AuthResponse = io {
        val body = JSONObject().put("username", username).put("password", authSecret)
        if (register && keys != null) {
            body.put("dh_pub", keys.dhPub).put("sig_pub", keys.sigPub).put("key_blob", keys.keyBlob)
        }
        val req = Request.Builder().url("$base/${if (register) "register" else "login"}")
            .post(body.toString().toRequestBody(jsonType)).build()
        val obj = JSONObject(call(req))
        AuthResponse(Session(obj.getString("token"), obj.getString("username")), obj.getString("key_blob"))
    }

    /** Без авторизации: опознавательный ответ сервера и версия, которую он ещё поддерживает. */
    suspend fun ping(): ServerInfo = io {
        val obj = JSONObject(call(Request.Builder().url("$base/api/ping").build()))
        val min = obj.optJSONObject("min_client_version")?.optString("android")?.takeUnless { it.isEmpty() }
        ServerInfo(obj.getString("version"), min)
    }

    /** Без авторизации: установщики клиентов, которые сейчас опубликованы на сервере. */
    suspend fun downloads(): List<DownloadItem> = io {
        val arr = JSONArray(call(Request.Builder().url("$base/api/downloads").build()))
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            DownloadItem(o.getString("name"), o.getString("url"), o.getString("platform"), o.optString("version").ifEmpty { null })
        }
    }

    suspend fun rooms(token: String): List<Room> = io {
        val arr = JSONArray(call(authed(token, "/rooms").build()))
        List(arr.length()) { parseRoom(arr.getJSONObject(it)) }
    }

    suspend fun discover(token: String): List<PublicRoom> = io {
        val arr = JSONArray(call(authed(token, "/rooms/discover").build()))
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            PublicRoom(o.getLong("id"), o.getString("name"), o.optString("kind", "group"), o.getInt("members"))
        }
    }

    suspend fun createRoom(token: String, name: String, kind: String = "group"): Room = io {
        parseRoom(JSONObject(call(authed(token, "/rooms").post(json("name" to name, "kind" to kind)).build())))
    }

    suspend fun joinRoom(token: String, roomId: Long): Room = io {
        val req = authed(token, "/rooms/$roomId/join").post("".toRequestBody(null)).build()
        parseRoom(JSONObject(call(req)))
    }

    suspend fun leaveRoom(token: String, roomId: Long) = io {
        call(authed(token, "/rooms/$roomId/leave").post(json()).build())
        Unit
    }

    suspend fun openDm(token: String, username: String): Room = io {
        parseRoom(JSONObject(call(authed(token, "/dm").post(json("username" to username)).build())))
    }

    suspend fun history(token: String, roomId: Long, beforeId: Long? = null, limit: Int = 50): List<RawMessage> = io {
        val q = if (beforeId != null) "&before_id=$beforeId" else ""
        val arr = JSONArray(call(authed(token, "/rooms/$roomId/messages?limit=$limit$q").build()))
        List(arr.length()) { parseRaw(arr.getJSONObject(it)) }
    }

    /** Загружает уже зашифрованный файл; ct/sig — зашифрованное сообщение с ключом файла. */
    suspend fun uploadMedia(
        token: String, roomId: Long, epoch: Int, ct: String, sig: String, body: RequestBody,
    ): RawMessage = io {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("epoch", epoch.toString())
            .addFormDataPart("ct", ct)
            .addFormDataPart("sig", sig)
            .addFormDataPart("file", "e.bin", body)
        parseRaw(JSONObject(call(authed(token, "/rooms/$roomId/media").post(form.build()).build())))
    }

    /** Только автор; epoch/ct/sig — новое содержимое, зашифрованное так же, как обычное сообщение. */
    suspend fun editMessage(token: String, roomId: Long, messageId: Long, epoch: Int, ct: String, sig: String): EditResult = io {
        val body = json("epoch" to epoch, "ct" to ct, "sig" to sig)
        val o = JSONObject(call(authed(token, "/rooms/$roomId/messages/$messageId").put(body).build()))
        EditResult(o.getLong("id"), o.getLong("room_id"), o.getInt("epoch"), o.getString("ct"), o.getString("sig"), o.getDouble("edited_ts"))
    }

    /** forEveryone=false — только у себя (сервер лишь прячет сообщение для вызывающего, доступно всем). */
    suspend fun deleteMessage(token: String, roomId: Long, messageId: Long, forEveryone: Boolean): Unit = io {
        call(authed(token, "/rooms/$roomId/messages/$messageId?for_everyone=$forEveryone").delete().build())
        Unit
    }

    // ---------- реакции, закрепление, самоудаление, прочтение ----------

    suspend fun setReaction(token: String, roomId: Long, messageId: Long, epoch: Int, ct: String, sig: String) = io {
        JSONObject(call(authed(token, "/rooms/$roomId/messages/$messageId/reaction").put(json("epoch" to epoch, "ct" to ct, "sig" to sig)).build()))
        Unit
    }

    suspend fun removeReaction(token: String, roomId: Long, messageId: Long) = io {
        call(authed(token, "/rooms/$roomId/messages/$messageId/reaction").delete().build())
        Unit
    }

    suspend fun pinMessage(token: String, roomId: Long, messageId: Long) = io {
        call(authed(token, "/rooms/$roomId/pin/$messageId").post(json()).build())
        Unit
    }

    suspend fun unpinMessage(token: String, roomId: Long, messageId: Long) = io {
        call(authed(token, "/rooms/$roomId/pin/$messageId").delete().build())
        Unit
    }

    suspend fun pins(token: String, roomId: Long): List<RawMessage> = io {
        val arr = JSONArray(call(authed(token, "/rooms/$roomId/pins").build()))
        List(arr.length()) { parseRaw(arr.getJSONObject(it)) }
    }

    suspend fun setAutodelete(token: String, roomId: Long, seconds: Int?) = io {
        val body = JSONObject().apply { put("seconds", seconds ?: JSONObject.NULL) }.toString().toRequestBody(jsonType)
        call(authed(token, "/rooms/$roomId/autodelete").put(body).build())
        Unit
    }

    // ---------- опросы ----------

    suspend fun setVote(token: String, roomId: Long, messageId: Long, epoch: Int, ct: String, sig: String) = io {
        JSONObject(call(authed(token, "/rooms/$roomId/messages/$messageId/vote").put(json("epoch" to epoch, "ct" to ct, "sig" to sig)).build()))
        Unit
    }

    suspend fun removeVote(token: String, roomId: Long, messageId: Long) = io {
        call(authed(token, "/rooms/$roomId/messages/$messageId/vote").delete().build())
        Unit
    }

    // ---------- отложенная отправка ----------

    suspend fun scheduleMessage(token: String, roomId: Long, epoch: Int, ct: String, sig: String, sendAt: Double): ScheduledMsg = io {
        val o = JSONObject(call(authed(token, "/rooms/$roomId/schedule").put(json("epoch" to epoch, "ct" to ct, "sig" to sig, "send_at" to sendAt)).build()))
        ScheduledMsg(o.getLong("id"), o.getLong("room_id"), o.getDouble("send_at"))
    }

    suspend fun listScheduled(token: String, roomId: Long): List<ScheduledMsg> = io {
        val arr = JSONArray(call(authed(token, "/rooms/$roomId/scheduled").build()))
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            ScheduledMsg(o.getLong("id"), o.getLong("room_id"), o.getDouble("send_at"))
        }
    }

    suspend fun cancelScheduled(token: String, roomId: Long, scheduledId: Long) = io {
        call(authed(token, "/rooms/$roomId/scheduled/$scheduledId").delete().build())
        Unit
    }

    // ---------- каталог стикеров (открытый, не E2EE — см. README §12) ----------

    suspend fun createStickerPack(token: String, name: String): StickerPack = io {
        parseStickerPack(JSONObject(call(authed(token, "/stickers/packs").post(json("name" to name)).build())))
    }

    suspend fun searchStickerPacks(token: String, query: String): List<StickerPack> = io {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val arr = JSONArray(call(authed(token, "/stickers/packs?q=$q").build()))
        List(arr.length()) { parseStickerPack(arr.getJSONObject(it)) }
    }

    suspend fun getStickerPack(token: String, packId: Long): StickerPack = io {
        parseStickerPack(JSONObject(call(authed(token, "/stickers/packs/$packId").build())))
    }

    suspend fun deleteStickerPack(token: String, packId: Long) = io {
        call(authed(token, "/stickers/packs/$packId").delete().build())
        Unit
    }

    suspend fun addSticker(token: String, packId: Long, body: RequestBody): Sticker = io {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "s.jpg", body)
        val o = JSONObject(call(authed(token, "/stickers/packs/$packId/stickers").post(form.build()).build()))
        Sticker(o.getLong("id"), o.getString("url"), o.optInt("position", 0))
    }

    suspend fun deleteSticker(token: String, packId: Long, stickerId: Long) = io {
        call(authed(token, "/stickers/packs/$packId/stickers/$stickerId").delete().build())
        Unit
    }

    suspend fun installStickerPack(token: String, packId: Long) = io {
        call(authed(token, "/stickers/packs/$packId/install").post(json()).build())
        Unit
    }

    suspend fun uninstallStickerPack(token: String, packId: Long) = io {
        call(authed(token, "/stickers/packs/$packId/install").delete().build())
        Unit
    }

    suspend fun installedStickerPacks(token: String): List<StickerPack> = io {
        val arr = JSONArray(call(authed(token, "/stickers/installed").build()))
        List(arr.length()) { parseStickerPack(arr.getJSONObject(it)) }
    }

    private fun parseStickerPack(o: JSONObject): StickerPack {
        val stickersArr = o.optJSONArray("stickers")
        val stickers = if (stickersArr == null) emptyList() else List(stickersArr.length()) {
            val s = stickersArr.getJSONObject(it)
            Sticker(s.getLong("id"), s.getString("url"), s.optInt("position", 0))
        }
        return StickerPack(
            id = o.getLong("id"), name = o.getString("name"), creatorUsername = o.getString("creator_username"),
            stickerCount = o.getInt("sticker_count"), created = o.getDouble("created"),
            installed = o.optBoolean("installed", false), stickers = stickers,
        )
    }

    /** username -> до какого id дочитал; себя сервер в список не включает. */
    suspend fun readState(token: String, roomId: Long): Map<String, Long> = io {
        val arr = JSONArray(call(authed(token, "/rooms/$roomId/read_state").build()))
        (0 until arr.length()).associate {
            val o = arr.getJSONObject(it)
            o.getString("username") to o.getLong("last_read_id")
        }
    }

    /** Публичный профиль: доступен любому по имени пользователя (в отличие от ключей — не требует общей комнаты). */
    suspend fun getProfile(token: String, username: String): Profile = io {
        val o = JSONObject(call(authed(token, "/users/$username/profile").build()))
        Profile(o.getString("username"), o.optString("bio", ""), if (o.isNull("avatar")) null else o.getString("avatar"))
    }

    suspend fun updateProfile(token: String, bio: String): Profile = io {
        val o = JSONObject(call(authed(token, "/profile").put(json("bio" to bio)).build()))
        Profile(o.getString("username"), o.optString("bio", ""), if (o.isNull("avatar")) null else o.getString("avatar"))
    }

    /** Отдаёт только новый URL аватара (не весь профиль) — этот эндпоинт био не знает и не трогает,
        так что заполнять Profile.bio тут было бы нечем и легко перепутать с настоящим значением. */
    /** [bytes] — уже расшифрованное на устройстве аудио (или видео с дорожкой звука): сервер не хранит ни
        его, ни результат, см. README §14. 503, если распознавание на сервере не включено. */
    suspend fun transcribe(token: String, bytes: ByteArray): String = io {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.m4a", bytes.toRequestBody("audio/*".toMediaType()))
        val o = JSONObject(call(authed(token, "/speech/transcribe").post(form.build()).build()))
        o.getString("text")
    }

    suspend fun uploadAvatar(token: String, bytes: ByteArray): String? = io {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "avatar.jpg", bytes.toRequestBody("image/*".toMediaType()))
        val o = JSONObject(call(authed(token, "/profile/avatar").post(form.build()).build()))
        if (o.isNull("avatar")) null else o.getString("avatar")
    }

    suspend fun deleteAvatar(token: String) = io {
        call(authed(token, "/profile/avatar").delete().build())
        Unit
    }

    // ---------- ключи шифрования ----------

    suspend fun keys(token: String, roomId: Long?): KeysResponse = io {
        val q = if (roomId != null) "?room_id=$roomId" else ""
        val o = JSONObject(call(authed(token, "/keys$q").build()))
        val latestObj = o.getJSONObject("latest")
        val latest = HashMap<Long, Int>()
        for (k in latestObj.keys()) latest[k.toLong()] = latestObj.getInt(k)
        val arr = o.getJSONArray("envelopes")
        KeysResponse(latest, List(arr.length()) {
            val e = arr.getJSONObject(it)
            Envelope(e.getLong("room_id"), e.getInt("epoch"), e.getString("sender"),
                e.getString("eph"), e.getString("n"), e.getString("c"), e.getString("sig"))
        })
    }

    suspend fun members(token: String, roomId: Long): List<Member> = io {
        val arr = JSONArray(call(authed(token, "/rooms/$roomId/members").build()))
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            Member(o.getString("username"), o.getString("dh_pub"), o.getString("sig_pub"))
        }
    }

    suspend fun userKeys(token: String, username: String): Member = io {
        val url = "$base/users".toHttpUrl().newBuilder().addPathSegment(username).addPathSegment("keys").build()
        val o = JSONObject(call(Request.Builder().url(url).header("Authorization", "Bearer $token").build()))
        Member(o.getString("username"), o.getString("dh_pub"), o.getString("sig_pub"))
    }

    /** Новая эпоха ключа комнаты: конверт для каждого участника. 409 — эпоху уже создал другой участник. */
    suspend fun postKeys(token: String, roomId: Long, epoch: Int, envelopes: JSONArray) = io {
        val body = JSONObject().put("epoch", epoch).put("envelopes", envelopes)
        call(authed(token, "/rooms/$roomId/keys").post(body.toString().toRequestBody(jsonType)).build())
    }

    /** Скачивает защищённый файл (/files/...) в dest. */
    suspend fun download(token: String, path: String, dest: File) = io {
        try {
            http.newCall(authed(token, path).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException("Не удалось загрузить файл", resp.code)
                val tmp = File(dest.parentFile, dest.name + ".part")
                resp.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(dest)
            }
        } catch (e: IOException) {
            throw ApiException("Нет связи с сервером")
        }
    }

    suspend fun iceServers(token: String): List<IceCfg> = io {
        val arr = JSONObject(call(authed(token, "/webrtc/config").build())).getJSONArray("iceServers")
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            val urls = o.getJSONArray("urls")
            IceCfg(
                urls = List(urls.length()) { i -> urls.getString(i) },
                username = o.optString("username").ifEmpty { null },
                credential = o.optString("credential").ifEmpty { null },
            )
        }
    }

    suspend fun registerDevice(token: String, fcmToken: String) = io {
        call(authed(token, "/devices").post(json("token" to fcmToken)).build())
    }

    suspend fun unregisterDevice(token: String, fcmToken: String) = io {
        val url = "$base/devices".toHttpUrl().newBuilder().addQueryParameter("token", fcmToken).build()
        call(Request.Builder().url(url).header("Authorization", "Bearer $token").delete().build())
    }

    /** http -> ws, https -> wss (OkHttp сам подменяет схему). */
    fun openSocket(token: String, listener: WebSocketListener): WebSocket {
        val wsBase = base.replaceFirst("http", "ws")
        return http.newWebSocket(Request.Builder().url("$wsBase/ws?token=$token").build(), listener)
    }

    private fun call(req: Request): String = try {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(errorText(text, resp.code), resp.code)
            text
        }
    } catch (e: IOException) {
        throw ApiException("Нет связи с сервером")
    }

    private fun errorText(body: String, code: Int): String = try {
        val detail = JSONObject(body).opt("detail")
        if (detail is String) detail else "Проверьте введённые данные"
    } catch (e: Exception) {
        "Ошибка сервера ($code)"
    }
}
