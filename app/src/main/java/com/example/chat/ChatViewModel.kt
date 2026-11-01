package com.example.chat

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.UUID

private const val PAGE = 50
private const val MAX_VIDEO_BYTES = 30L * 1024 * 1024
private const val MAX_DOC_BYTES = 32L * 1024 * 1024

/** Имя и размер файла, выбранного через SAF (content://…) — как показывает системный проводник. */
private fun fileNameAndSize(app: Application, uri: Uri): Pair<String?, Long?> {
    var name: String? = null; var size: Long? = null
    try {
        app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) name = cursor.getString(nameIdx)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
            }
        }
    } catch (e: Exception) { /* имя необязательно — покажем «Файл» */ }
    return name to size
}

enum class CallPhase { RINGING, INCOMING, CONNECTING, ACTIVE }

/** Сигнал (SDP/ICE) от конкретного участника звонка — callId, кто прислал (id и имя, имя нужно для расшифровки) и сам зашифрованный payload. */
data class CallSignalIn(val callId: String, val peerId: Long, val fromUsername: String, val data: JSONObject)

data class CallUi(
    val callId: String,
    val roomId: Long,
    val roomName: String,
    val video: Boolean,
    val outgoing: Boolean,
    val group: Boolean,
    val phase: CallPhase,
    val participants: Map<Long, String> = emptyMap(),   // userId -> username, кто уже подключён к звонку
    val muted: Boolean = false,
    val cameraOff: Boolean = false,
    val speaker: Boolean = false,
    val onHold: Boolean = false,     // отняли аудио-фокус (например, ответили на обычный звонок) — сами на удержании
    val startedAt: Long? = null,
)

data class UiState(
    val serverUrl: String,
    val session: Session? = null,
    val rooms: List<Room> = emptyList(),
    val currentRoomId: Long? = null,
    val messages: Map<Long, List<ChatMessage>> = emptyMap(),
    val typing: Map<Long, Set<String>> = emptyMap(),
    val connected: Boolean = false,
    val loading: Boolean = false,
    val uploading: Boolean = false,
    val discover: List<PublicRoom>? = null, // null = список ещё не загружен
    val video: File? = null,                // видеосообщение, которое сейчас смотрим
    val call: CallUi? = null,
    val security: SecurityInfo? = null,     // экран «Безопасность»
    val update: UpdateInfo? = null,          // уведомление/требование обновиться (см. Update.kt)
    val replyTo: ChatMessage? = null,        // на какое сообщение отвечаем в открытом чате
    val editingId: Long? = null,             // id редактируемого сообщения в открытом чате
    val error: String? = null,
    val aliases: Map<String, String> = emptyMap(),   // псевдонимы контактов — только на этом устройстве
    val mutedRooms: Set<Long> = emptySet(),           // заглушённые чаты — только на этом устройстве
    val archivedRooms: Set<Long> = emptySet(),        // архивированные чаты — только на этом устройстве
    val hiddenRooms: Set<Long> = emptySet(),          // удалённые личные чаты — тоже только на этом устройстве
    val showArchived: Boolean = false,                // развёрнут ли раздел «Архив» в списке чатов
    val showingProfile: Boolean = false,     // открыт ли экран «Мой профиль»
    val myProfile: Profile? = null,          // null, пока не загрузился
    val contactProfile: ContactProfileUi? = null,    // открыт ли диалог профиля собеседника в чате
    val readState: Map<Long, Map<String, Long>> = emptyMap(),   // roomId -> {username: last_read_id} — галочки
    val transcripts: Map<Long, String> = emptyMap(),   // messageId -> распознанный текст (только на этом устройстве)
    val transcribing: Set<Long> = emptySet(),          // messageId, для которых сейчас идёт запрос к серверу
    val scheduled: Map<Long, List<ScheduledMsg>> = emptyMap(),  // roomId -> свои отложенные сообщения этой комнаты
    val installedStickerPacks: List<StickerPack> = emptyList(),
    val stickerTrayOpen: Boolean = false,
    val stickerCatalog: List<StickerPack>? = null,   // null — каталог не открыт; иначе результаты поиска
    val stickerCatalogQuery: String = "",
    val stickerPackDetail: StickerPack? = null,      // открыт ли просмотр одного пака
    val pinnedBar: Map<Long, List<ChatMessage>> = emptyMap(),   // roomId -> закреплённые (расшифрованные)
)

/** Что показать в окне про шифрование: пояснение и (для личных чатов) номер безопасности. */
data class SecurityInfo(val title: String, val text: String, val number: String?)

/** Диалог профиля собеседника из чата: bio — null, пока не загрузился с сервера. */
data class ContactProfileUi(val username: String, val bio: String?, val alias: String)

private val LOCKED_TEXT = mapOf(
    "no_key" to "🔒 Сообщение недоступно: отправлено до вашего вступления в комнату",
    "bad_sig" to "⚠️ Подпись сообщения не прошла проверку",
    "key_changed" to "⚠️ Ключ безопасности отправителя изменился",
    "bad_data" to "⚠️ Не удалось расшифровать сообщение",
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    // Для продакшена храните токен в EncryptedSharedPreferences
    private val prefs = app.getSharedPreferences("chat", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        UiState(
            serverUrl = SERVER_URL,
            session = savedSession(),
            aliases = loadAliases(),
            mutedRooms = loadRoomIdSet("mute_"),
            archivedRooms = loadRoomIdSet("archive_"),
            hiddenRooms = loadRoomIdSet("hidden_"),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Сохранённая сессия. Сессии и ключи с другого сервера (прежние сборки) здесь недействительны и стираются. */
    private fun savedSession(): Session? {
        val saved = prefs.getString("server", null)
        if (saved != null && saved.trim().trimEnd('/') != SERVER_URL) {
            prefs.edit().clear().apply()
            IdentityStore.clear(getApplication<Application>())
            return null
        }
        return prefs.getString("token", null)?.let { t -> prefs.getString("user", null)?.let { Session(t, it) } }
    }

    private var api: ApiClient? = _state.value.session?.let { ApiClient(_state.value.serverUrl) }
    private val updateClient = ApiClient()   // ping/downloads не требуют входа — версию проверяем независимо от сессии
    private var socket: WebSocket? = null
    private var generation = 0          // отсекает колбэки от устаревших сокетов
    private var attempt = 0
    private var foreground = false      // сокет живёт только пока приложение на экране (или идёт звонок)
    private var pushRegistered = false
    private var reconnectJob: Job? = null
    private var lastTypingSent = 0L
    private val typingJobs = HashMap<String, Job>()
    private val loaded = HashSet<Long>()        // комнаты, чья история уже в state
    private val exhausted = HashSet<Long>()     // комнаты, где загружена вся история
    private val loadingOlder = HashSet<Long>()

    private var e2ee: E2ee? = null
    // события, которым важен порядок (сообщения, ключи), обрабатываются по одному
    private val eventQueue = Channel<JSONObject>(Channel.UNLIMITED)
    private val signalOut = Channel<Triple<String, Long, JSONObject>>(Channel.UNLIMITED)   // callId, peerId, открытый сигнал
    private val signalIn = Channel<CallSignalIn>(Channel.UNLIMITED)                        // callId, от кого (id+имя), зашифрованный

    private val callLazy = lazy {
        CallManager(
            app = getApplication<Application>(),
            // SDP и ICE шифруются ключом конкретного участника (по порядку, через очередь) и только потом уходят на сервер
            onSignal = { peerId, payload -> _state.value.call?.let { c -> signalOut.trySend(Triple(c.callId, peerId, payload)) } },
            onPeerConnected = { peerId -> markPeerConnected(peerId) },
            onPeerFailed = { peerId -> onPeerConnectionFailed(peerId) },
            onHoldChanged = { held -> onCallHoldChanged(held) },
        )
    }
    val callManager: CallManager get() = callLazy.value

    init {
        restoreE2ee()
        checkForUpdate()
        AvatarCache.token = _state.value.session?.token
        viewModelScope.launch { _state.collect { AvatarCache.token = it.session?.token } }
        viewModelScope.launch {
            for (o in eventQueue) {
                try {
                    processQueued(o)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // одно проблемное событие не должно останавливать очередь
                }
            }
        }
        viewModelScope.launch { for ((callId, peerId, payload) in signalOut) sendSealedSignal(callId, peerId, payload) }
        viewModelScope.launch { for (s in signalIn) openIncomingSignal(s.callId, s.peerId, s.fromUsername, s.data) }
    }

    /** При запуске поднимаем ключи из защищённого хранилища; нет ключей — нужно войти заново. */
    private fun restoreE2ee() {
        val s = _state.value.session ?: return
        val app = getApplication<Application>()
        val id = IdentityStore.load(app, s.username)
        var restored: E2ee? = null
        var problem: String? = null
        if (id == null) {
            problem = "Войдите заново: на этом устройстве нет ключей шифрования"
        } else {
            try {
                restored = E2ee(api ?: ApiClient(_state.value.serverUrl), s.token, s.username, id, Pins(app, _state.value.serverUrl))
            } catch (e: E2eeException) {
                problem = e.message
            }
        }
        if (restored == null) {
            prefs.edit().remove("token").remove("user").remove("fcm").apply()
            api = null
            _state.update { it.copy(session = null, error = problem) }
        } else {
            e2ee = restored
        }
    }

    // ---------- обновление приложения ----------

    /** Спрашивает сервер, актуальна ли версия приложения. Сбой сети не должен мешать работе. */
    private fun checkForUpdate() {
        viewModelScope.launch {
            try {
                val info = updateClient.ping()
                val downloads = updateClient.downloads()
                val update = decideUpdate(APP_VERSION, info.minClientVersion, downloads) ?: return@launch
                if (update.severity == UpdateSeverity.OPTIONAL && prefs.getString("dismissed_update", null) == update.latest) return@launch
                _state.update { it.copy(update = update) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // тихо: проверка обновлений не должна мешать работе приложения
            }
        }
    }

    /** Ручная проверка из списка чатов: в отличие от автоматической, не молчит и не учитывает «скрыть» — всегда
        говорит, что именно увидело приложение (свою версию и опубликованный на сервере .apk), чтобы было понятно,
        почему баннер обновления есть или его нет. */
    fun checkForUpdateManually() {
        viewModelScope.launch {
            try {
                val info = updateClient.ping()
                val downloads = updateClient.downloads()
                val apk = downloads.firstOrNull { it.platform == "android" }
                val update = decideUpdate(APP_VERSION, info.minClientVersion, downloads)
                if (update != null) {
                    _state.update { it.copy(update = update, error = null) }
                } else {
                    showError(
                        when {
                            apk == null -> "Установлена версия $APP_VERSION. На сервере нет .apk (имя файла должно быть вида Freedom-X.Y.apk)"
                            !isValidVersion(apk.version) -> "Установлена версия $APP_VERSION. В имени ${apk.name} не найден номер версии вида X.Y"
                            else -> "Установлена версия $APP_VERSION — обновлений нет (на сервере ${apk.version})"
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("Не удалось проверить обновления: ${e.message ?: "нет связи с сервером"}")
            }
        }
    }

    /** Только для необязательного обновления — обязательное показывается снова при каждом запуске. */
    fun dismissUpdate() {
        val u = _state.value.update ?: return
        if (u.severity == UpdateSeverity.OPTIONAL) prefs.edit().putString("dismissed_update", u.latest).apply()
        _state.update { it.copy(update = null) }
    }

    fun openUpdateDownload() {
        val url = _state.value.update?.url ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SERVER_URL.trimEnd('/') + url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        } catch (e: Exception) {
            showError("Не удалось открыть браузер")
        }
    }

    // ---------- вход / выход ----------

    fun authenticate(register: Boolean, username: String, password: String) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val client = ApiClient()
                val name = username.trim()
                // из пароля выводятся два независимых секрета: auth (уходит на сервер) и kek (остаётся здесь)
                val secrets = withContext(Dispatchers.Default) { Onboarding.deriveSecrets(name, password) }
                val resp: AuthResponse
                val identity: Identity
                if (register) {
                    val fresh = withContext(Dispatchers.Default) { Onboarding.createIdentity() }
                    val blob = withContext(Dispatchers.Default) { Onboarding.sealBlob(fresh, secrets.kek, name) }
                    resp = client.authenticate(
                        true, name, secrets.auth,
                        RegisterKeys(Crypto.b64(fresh.dhSpki), Crypto.b64(fresh.sigSpki), blob),
                    )
                    identity = fresh
                } else {
                    resp = client.authenticate(false, name, secrets.auth, null)
                    identity = withContext(Dispatchers.Default) { Onboarding.openBlob(resp.keyBlob, secrets.kek, name) }
                }
                val session = resp.session
                val app = getApplication<Application>()
                e2ee = E2ee(client, session.token, session.username, identity, Pins(app, SERVER_URL))
                IdentityStore.save(app, session.username, identity)
                prefs.edit()
                    .putString("server", SERVER_URL)
                    .putString("token", session.token)
                    .putString("user", session.username)
                    .apply()
                api = client
                _state.update { it.copy(session = session, loading = false) }
                connect()
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: E2eeException) {
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: java.security.GeneralSecurityException) {
                _state.update { it.copy(loading = false, error = "Не удалось сохранить ключи шифрования на устройстве") }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Неверный адрес сервера") }
            }
        }
    }

    fun logout() {
        val client = api
        val token = _state.value.session?.token
        val fcm = prefs.getString("fcm", null)
        if (client != null && token != null && fcm != null) {
            // перестаём получать push на этом устройстве
            CoroutineScope(Dispatchers.IO).launch { runCatching { client.unregisterDevice(token, fcm) } }
        }
        if (_state.value.call != null) cleanupCall(null)
        VoicePlayer.stop()
        MediaCache.clear(getApplication<Application>())
        IdentityStore.clear(getApplication<Application>())
        e2ee = null
        disconnect()
        api = null
        pushRegistered = false
        loaded.clear(); exhausted.clear(); loadingOlder.clear()
        typingJobs.values.forEach { it.cancel() }
        typingJobs.clear()
        prefs.edit().remove("token").remove("user").remove("fcm").apply()
        _state.update { UiState(serverUrl = it.serverUrl) }
    }

    /** Вызывается из Activity: onStart -> true, onStop -> false. */
    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (_state.value.session == null) return
        if (value) {
            attempt = 0
            connect()
        } else if (_state.value.call == null) {
            disconnect() // во время звонка сокет нужен и в фоне
        }
    }

    // ---------- подключение ----------

    private fun wantConnection() = foreground || _state.value.call != null

    private fun connect() {
        if (!wantConnection()) return
        val client = api ?: return
        val token = _state.value.session?.token ?: return
        reconnectJob?.cancel()
        viewModelScope.launch {
            try {
                val rooms = loadRoomsDecrypted(client, token)
                val open = _state.value.currentRoomId
                // за время офлайна могли пропасть сообщения: держим только открытую комнату
                loaded.retainAll { it == open }
                _state.update { s ->
                    s.copy(
                        rooms = rooms.map { if (it.id == open) it.copy(unread = 0) else it },
                        messages = s.messages.filterKeys { it == open },
                    )
                }
                if (open != null) addMessages(open, decryptAll(client.history(token, open, limit = PAGE)))
            } catch (e: ApiException) {
                if (e.code == 401) logout() else scheduleReconnect()
                return@launch
            }
            if (!wantConnection()) return@launch
            openSocket(client, token)
            registerPush(client, token)
        }
    }

    private fun disconnect() {
        generation++
        reconnectJob?.cancel()
        socket?.close(1000, null)
        socket = null
        _state.update { it.copy(connected = false) }
    }

    private fun openSocket(client: ApiClient, token: String) {
        socket?.cancel()
        val gen = ++generation
        socket = client.openSocket(token, object : WebSocketListener() {
            // колбэки приходят из потока OkHttp; переносим их в главный поток,
            // чтобы всё состояние VM менялось последовательно
            override fun onOpen(webSocket: WebSocket, response: Response) {
                viewModelScope.launch {
                    if (gen != generation) return@launch
                    attempt = 0
                    _state.update { it.copy(connected = true) }
                    val room = _state.value.currentRoomId
                    room?.let { r -> _state.value.messages[r]?.lastOrNull()?.let { sendRead(r, it.id) } }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                viewModelScope.launch { if (gen == generation) onSocketEvent(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                viewModelScope.launch { dropped(gen, unauthorized = code == 4401) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val unauthorized = response?.code == 401 || response?.code == 403
                viewModelScope.launch { dropped(gen, unauthorized) }
            }
        })
    }

    private fun dropped(gen: Int, unauthorized: Boolean) {
        if (gen != generation) return
        _state.update { it.copy(connected = false) }
        if (unauthorized) logout() else scheduleReconnect()
    }

    /** Переподключение с нарастающей задержкой: 1, 2, 4 ... до 30 секунд. */
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(minOf(30_000L, 1_000L shl minOf(attempt, 5)))
            attempt++
            connect()
        }
    }

    private fun registerPush(client: ApiClient, token: String) {
        if (pushRegistered) return
        pushRegistered = true
        Push.currentToken(getApplication<Application>()) { fcm ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    client.registerDevice(token, fcm)
                    prefs.edit().putString("fcm", fcm).apply()
                }
            }
        }
    }

    private fun sendJson(o: JSONObject) {
        socket?.send(o.toString())
    }

    // ---------- события сокета ----------

    private fun onSocketEvent(text: String) {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
        try {
            when (o.optString("type")) {
                "message", "keys_updated", "rooms_changed", "message_edited", "message_deleted",
                "reaction_set", "reaction_removed", "message_pinned", "message_unpinned",
                "autodelete_changed", "read_state", "vote_set", "vote_removed" -> eventQueue.trySend(o)
                "typing" -> onTyping(o.getLong("room_id"), o.getString("username"))
                "error" -> onServerError(o)
                "call_invite" -> onCallInvite(o)
                "call_joined" -> onCallJoined(o)
                "call_peer_joined" -> onCallPeerJoined(o)
                "call_peer_left" -> onCallPeerLeft(o)
                "call_signal" -> {
                    val c = _state.value.call
                    if (c != null && o.getString("call_id") == c.callId) {
                        signalIn.trySend(CallSignalIn(c.callId, o.getLong("from_id"), o.optString("from"), o.getJSONObject("data")))
                    }
                }
                "call_end" -> onCallEnd(o.getString("call_id"), o.optString("reason"))
            }
        } catch (e: JSONException) {
            // некорректное событие — игнорируем
        }
    }

    private fun handleMessage(m: ChatMessage) {
        val s = _state.value
        val open = s.currentRoomId == m.roomId
        val room = s.rooms.firstOrNull { it.id == m.roomId }

        typingJobs.remove("${m.roomId}:${m.username}")?.cancel()
        clearTyping(m.roomId, m.username)

        // сообщения складываем только в уже загруженные комнаты, иначе при открытии
        // список окажется «дырявым»; для остальных достаточно превью и счётчика
        if (m.roomId in loaded) addMessages(m.roomId, listOf(m))

        if (room == null) {
            refreshRooms() // например, кто-то написал нам первым в личку
        } else {
            val mine = m.username == s.session?.username
            _state.update { st ->
                st.copy(rooms = st.rooms.map { r ->
                    if (r.id != m.roomId) r else r.copy(
                        lastPreview = previewOf(r.isDirect, m.username, m.text, m.kind, m.fileName),
                        sortTs = m.ts,
                        unread = if (open || mine || m.kind == "call") r.unread else r.unread + 1,
                    )
                }.sortedByDescending { it.sortTs })
            }
        }
        if (open) sendRead(m.roomId, m.id)
    }

    private fun onTyping(roomId: Long, user: String) {
        _state.update { it.copy(typing = it.typing + (roomId to ((it.typing[roomId] ?: emptySet()) + user))) }
        val key = "$roomId:$user"
        typingJobs[key]?.cancel()
        typingJobs[key] = viewModelScope.launch {
            delay(4000) // если новых событий нет, «печатает…» гаснет само
            typingJobs.remove(key)
            clearTyping(roomId, user)
        }
    }

    private fun clearTyping(roomId: Long, user: String) {
        _state.update { s ->
            val set = (s.typing[roomId] ?: emptySet()) - user
            s.copy(typing = if (set.isEmpty()) s.typing - roomId else s.typing + (roomId to set))
        }
    }

    // ---------- сообщения ----------

    fun openRoom(id: Long) {
        if (_state.value.session == null) return
        _state.update { s ->
            s.copy(currentRoomId = id, rooms = s.rooms.map { if (it.id == id) it.copy(unread = 0) else it })
        }
        loaded.add(id)
        call { c, t ->
            // при необходимости создаёт ключ комнаты (для нового участника — новую эпоху)
            try { e2ee?.ensureKey(id) } catch (e: E2eeException) { showError(e.message) }
            val h = c.history(t, id, limit = PAGE)
            addMessages(id, decryptAll(h))
            if (h.size < PAGE) exhausted.add(id)
            try {
                val rs = c.readState(t, id)
                _state.update { s -> s.copy(readState = s.readState + (id to rs)) }
            } catch (ex: ApiException) { /* не критично — просто не будет галочек до первого живого read_state */ }
            try {
                val pins = c.pins(t, id).map { openMsg(it) }
                _state.update { s -> s.copy(pinnedBar = s.pinnedBar + (id to pins)) }
            } catch (ex: ApiException) { /* не критично — просто не будет панели закреплённых */ }
            h.lastOrNull()?.let { sendRead(id, it.id) }
        }
    }

    fun closeRoom() = _state.update { it.copy(currentRoomId = null, replyTo = null, editingId = null) }

    /** Черновики — только на этом устройстве (SharedPreferences), между устройствами не синхронизируются. */
    fun saveDraft(roomId: Long, text: String) {
        val key = "draft_$roomId"
        if (text.isBlank()) prefs.edit().remove(key).apply() else prefs.edit().putString(key, text).apply()
    }
    fun loadDraft(roomId: Long): String = prefs.getString("draft_$roomId", "") ?: ""
    fun hasDraft(roomId: Long): Boolean = !prefs.getString("draft_$roomId", null).isNullOrEmpty()

    /** Псевдонимы — тоже только на этом устройстве, не синхронизируются. Живут и в prefs (переживают
        перезапуск), и в UiState.aliases (чтобы Compose перерисовался сразу же, без чтения prefs на каждый кадр). */
    private fun loadAliases(): Map<String, String> =
        prefs.all.entries.filter { it.key.startsWith("alias_") && it.value is String }
            .associate { it.key.removePrefix("alias_") to it.value as String }

    /** Загружает набор id комнат из ключей вида "<prefix><roomId>" со значением true (заглушено/архивировано). */
    private fun loadRoomIdSet(prefix: String): Set<Long> =
        prefs.all.entries.filter { it.key.startsWith(prefix) && it.value == true }
            .mapNotNull { it.key.removePrefix(prefix).toLongOrNull() }.toSet()

    fun setMuted(roomId: Long, muted: Boolean) {
        val key = "mute_$roomId"
        if (muted) prefs.edit().putBoolean(key, true).apply() else prefs.edit().remove(key).apply()
        _state.update { s -> s.copy(mutedRooms = if (muted) s.mutedRooms + roomId else s.mutedRooms - roomId) }
    }

    fun setArchived(roomId: Long, archived: Boolean) {
        val key = "archive_$roomId"
        if (archived) prefs.edit().putBoolean(key, true).apply() else prefs.edit().remove(key).apply()
        _state.update { s -> s.copy(archivedRooms = if (archived) s.archivedRooms + roomId else s.archivedRooms - roomId) }
    }

    fun toggleShowArchived() {
        _state.update { s -> s.copy(showArchived = !s.showArchived) }
    }

    /** Личный чат — просто скрывается у себя (переписка остаётся у собеседника, сервер её не трогает —
        соответствует тому, что "удалить чат" в мессенджерах обычно значит именно это, не отзыв сообщений).
        Группа/канал — по-настоящему выходим через сервер (см. README §13); при выходе владельца сервер сам
        передаёт владение дальше, это не забота клиента. */
    fun deleteRoom(room: Room) {
        if (room.isDirect) {
            val key = "hidden_${room.id}"
            prefs.edit().putBoolean(key, true).apply()
            _state.update { s -> s.copy(hiddenRooms = s.hiddenRooms + room.id) }
        } else {
            call { c, t ->
                c.leaveRoom(t, room.id)
                _state.update { s -> s.copy(rooms = s.rooms.filterNot { it.id == room.id }) }
            }
        }
    }

    fun saveAlias(username: String, alias: String) {
        val key = "alias_$username"
        val v = alias.trim()
        if (v.isEmpty()) prefs.edit().remove(key).apply() else prefs.edit().putString(key, v).apply()
        _state.update { s ->
            val aliases = if (v.isEmpty()) s.aliases - username else s.aliases + (username to v)
            s.copy(aliases = aliases, contactProfile = null)
        }
    }

    /** Открывает экран своего профиля; био подгружается отдельно (как и остальные экраны в этом файле). */
    fun openMyProfile() {
        val me = _state.value.session?.username ?: return
        _state.update { it.copy(showingProfile = true, myProfile = null) }
        call { c, t -> val p = c.getProfile(t, me); _state.update { s -> s.copy(myProfile = p) } }
    }
    fun dismissMyProfile() = _state.update { it.copy(showingProfile = false) }

    fun saveMyProfile(bio: String, onDone: () -> Unit) {
        call { c, t ->
            c.updateProfile(t, bio)
            dismissMyProfile()
            onDone()
        }
    }

    fun uploadMyAvatar(uri: Uri) {
        call { c, t ->
            val app = getApplication<Application>()
            val bytes = withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            if (bytes == null) { showError("Не удалось прочитать файл"); return@call }
            val old = _state.value.myProfile?.avatar
            val newUrl = c.uploadAvatar(t, bytes)
            AvatarCache.invalidate(old)
            _state.update { s -> s.copy(myProfile = s.myProfile?.copy(avatar = newUrl)) }
        }
    }

    fun deleteMyAvatar() {
        call { c, t ->
            val old = _state.value.myProfile?.avatar
            c.deleteAvatar(t)
            AvatarCache.invalidate(old)
            _state.update { s -> s.copy(myProfile = s.myProfile?.copy(avatar = null)) }
        }
    }

    fun openContactProfile(username: String) {
        val alias = _state.value.aliases[username] ?: ""
        _state.update { it.copy(contactProfile = ContactProfileUi(username, null, alias)) }
        call { c, t ->
            val p = c.getProfile(t, username)
            _state.update { s -> if (s.contactProfile?.username == username) s.copy(contactProfile = s.contactProfile.copy(bio = p.bio)) else s }
        }
    }
    fun dismissContactProfile() = _state.update { it.copy(contactProfile = null) }

    /** Шифрует и подписывает сообщение на устройстве; onSent вызывается, когда оно ушло на сервер. */
    fun send(roomId: Long, text: String, onSent: () -> Unit) {
        val t = text.trim()
        if (t.isEmpty()) return
        val e = e2ee ?: return
        if (!_state.value.connected) { showError("Нет соединения с сервером"); return }
        val replyTo = _state.value.replyTo
        viewModelScope.launch {
            try {
                val payload = JSONObject().put("v", 1).put("t", "text").put("text", t)
                if (replyTo != null) payload.put("reply_to", replyRefJson(replyTo))
                val sealed = e.seal(roomId, payload)
                val ok = socket?.send(
                    JSONObject().put("type", "message").put("room_id", roomId)
                        .put("epoch", sealed.epoch).put("ct", sealed.ct).put("sig", sealed.sig).toString()
                ) == true
                if (ok) { onSent(); cancelReply() } else showError("Нет соединения с сервером")
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: ApiException) {
                showError(ex.message)
            } catch (ex: E2eeException) {
                showError(ex.message)
            }
        }
    }

    /** Короткий снимок сообщения для цитаты «в ответ на…» — не меняется, если оригинал потом отредактируют. */
    private fun replyRefJson(m: ChatMessage) = JSONObject().put("id", m.id).put("username", m.username).put("t", m.kind)
        .put("text", if (m.kind == "file") (m.fileName ?: "Файл") else if (m.kind == "text") m.text
             else mapOf("image" to "Фото", "audio" to "Голосовое сообщение", "video" to "Видеосообщение")[m.kind] ?: "")

    fun startReply(m: ChatMessage) { _state.update { it.copy(replyTo = m, editingId = null) } }
    fun cancelReply() { _state.update { it.copy(replyTo = null) } }

    fun startEdit(m: ChatMessage) { _state.update { it.copy(editingId = m.id, replyTo = null) } }
    fun cancelEdit() { _state.update { it.copy(editingId = null) } }

    fun typing(roomId: Long) {
        val now = System.currentTimeMillis()
        if (now - lastTypingSent < 2500) return // не чаще раза в 2,5 с
        lastTypingSent = now
        socket?.send(JSONObject().put("type", "typing").put("room_id", roomId).toString())
    }

    /** Отправка готового файла (голосовое, видео). Файл удаляется после отправки. */
    fun sendMedia(roomId: Long, kind: String, file: File, filename: String, mime: String, duration: Double? = null) {
        if (file.length() > MAX_VIDEO_BYTES) {
            file.delete()
            showError("Файл больше 30 МБ")
            return
        }
        call { c, t -> uploadEncrypted(c, t, roomId, kind, file, mime, duration, if (kind == "file") filename else null) }
    }

    fun sendImage(roomId: Long, uri: Uri) {
        call { c, t ->
            val f = withContext(Dispatchers.IO) {
                val bytes = ImageUtils.compress(getApplication<Application>(), uri)
                File(getApplication<Application>().cacheDir, "img_${System.nanoTime()}.jpg").also { it.writeBytes(bytes) }
            }
            uploadEncrypted(c, t, roomId, "image", f, "image/jpeg", null)
        }
    }

    /** Файл любого типа (документ) — тем же шифрованием, что и остальные вложения. До 32 МБ. */
    fun sendFile(roomId: Long, uri: Uri) {
        call { c, t ->
            val app = getApplication<Application>()
            val (name, size) = withContext(Dispatchers.IO) { fileNameAndSize(app, uri) }
            if (size != null && size > MAX_DOC_BYTES) { showError("Файл больше 32 МБ"); return@call }
            val mime = app.contentResolver.getType(uri) ?: "application/octet-stream"
            val f = withContext(Dispatchers.IO) {
                File(app.cacheDir, "doc_${System.nanoTime()}").also { dst ->
                    app.contentResolver.openInputStream(uri)?.use { input -> dst.outputStream().use { input.copyTo(it) } }
                }
            }
            if (f.length() > MAX_DOC_BYTES) { f.delete(); showError("Файл больше 32 МБ"); return@call }
            uploadEncrypted(c, t, roomId, "file", f, mime, null, name ?: "Файл")
        }
    }

    /** Шифрует файл на устройстве; на сервер уходит только шифртекст. Исходный файл удаляется.
        name — оригинальное имя документа (только для kind="file"). */
    private suspend fun uploadEncrypted(
        c: ApiClient, t: String, roomId: Long, kind: String, file: File, mime: String, duration: Double?, name: String? = null,
    ) {
        val e = e2ee
        if (e == null) { file.delete(); return }
        val replyTo = _state.value.replyTo
        _state.update { it.copy(uploading = true) }
        val enc = File(getApplication<Application>().cacheDir, "up_${System.nanoTime()}.enc")
        try {
            val seconds = if (kind == "file") null else duration ?: withContext(Dispatchers.IO) { mediaDuration(file) }
            val secret = withContext(Dispatchers.IO) { e.encryptFile(file, enc) }
            val payload = JSONObject().put("v", 1).put("t", kind)
            if (seconds != null) payload.put("dur", seconds)
            if (name != null) payload.put("name", name)
            if (replyTo != null) payload.put("reply_to", replyRefJson(replyTo))
            payload.put("file", JSONObject().put("k", secret.key).put("n", secret.nonce).put("size", secret.size).put("mime", mime))
            var attempt = 0
            while (true) {
                val sealed = e.seal(roomId, payload)
                try {
                    val raw = c.uploadMedia(
                        t, roomId, sealed.epoch, sealed.ct, sealed.sig,
                        enc.asRequestBody("application/octet-stream".toMediaType()),
                    )
                    handleMessage(openMsg(raw)) // дубль из сокета отсеется по id
                    cancelReply()
                    break
                } catch (ex: ApiException) {
                    if (ex.code == 409 && attempt++ < 2) { e.fetchKeys(roomId); continue } // ключи комнаты обновились
                    throw ex
                }
            }
        } finally {
            file.delete()
            enc.delete()
            _state.update { it.copy(uploading = false) }
        }
    }

    fun loadOlder(roomId: Long) {
        if (roomId in exhausted || !loadingOlder.add(roomId)) return
        val first = _state.value.messages[roomId]?.firstOrNull()?.id
        if (first == null) { loadingOlder.remove(roomId); return }
        call { c, t ->
            try {
                val older = c.history(t, roomId, beforeId = first, limit = PAGE)
                if (older.size < PAGE) exhausted.add(roomId)
                addMessages(roomId, decryptAll(older))
            } finally {
                loadingOlder.remove(roomId)
            }
        }
    }

    /** Скачивает и расшифровывает вложение (результат кэшируется в приватной папке приложения). */
    suspend fun mediaFile(m: ChatMessage): File? {
        val c = api ?: return null
        val t = _state.value.session?.token ?: return null
        val e = e2ee ?: return null
        return try {
            MediaCache.file(getApplication<Application>(), c, t, e, m)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            null
        }
    }

    /** Расшифровывает голосовое/видеосообщение на устройстве и отправляет уже открытые байты на сервер для
        распознавания — единственное место в клиенте, где расшифрованное содержимое уходит за пределы
        устройства (см. README §14). Результат живёт только в памяти этого экрана, сервер его не хранит. */
    fun transcribe(m: ChatMessage) {
        if (_state.value.transcripts.containsKey(m.id) || _state.value.transcribing.contains(m.id)) return
        _state.update { it.copy(transcribing = it.transcribing + m.id) }
        viewModelScope.launch {
            val f = mediaFile(m)
            val c = api; val t = _state.value.session?.token
            val text = if (f != null && c != null && t != null) {
                try {
                    c.transcribe(t, f.readBytes())
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: ApiException) {
                    if (ex.code == 503) "Распознавание речи не включено на сервере" else null
                } catch (ex: Exception) {
                    null
                }
            } else null
            _state.update { s ->
                s.copy(
                    transcribing = s.transcribing - m.id,
                    transcripts = if (text != null) s.transcripts + (m.id to text) else s.transcripts,
                )
            }
            if (text == null) showError("Не удалось распознать речь")
        }
    }

    /** Стикеры не шифруются (открытый каталог) — просто скачиваем и кэшируем на диске по id, без MediaCache. */
    private val stickerFileCache = mutableMapOf<Long, File>()
    suspend fun stickerThumbFile(sticker: Sticker): File? {
        stickerFileCache[sticker.id]?.let { if (it.exists()) return it }
        val c = api ?: return null
        val t = _state.value.session?.token ?: return null
        return try {
            val dst = File(getApplication<Application>().cacheDir, "stkthumb_${sticker.id}")
            c.download(t, sticker.url, dst)
            stickerFileCache[sticker.id] = dst
            dst
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            null
        }
    }

    /** Расшифровывает файл и сохраняет его в системную папку «Загрузки» (Android 10+). */
    fun saveFileToDownloads(m: ChatMessage, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                if (Build.VERSION.SDK_INT < 29) {
                    showError("Сохранение файлов доступно на Android 10 и новее")
                    return@launch
                }
                val src = mediaFile(m) ?: throw ApiException("Не удалось скачать файл")
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, m.fileName ?: "file")
                        put(MediaStore.MediaColumns.MIME_TYPE, m.file?.mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val resolver = app.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw ApiException("Не удалось сохранить файл")
                    resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                        ?: throw ApiException("Не удалось сохранить файл")
                }
                showError("Файл сохранён в Загрузки")
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                showError("Не удалось сохранить файл")
            } finally {
                onDone()
            }
        }
    }

    fun toggleAudio(m: ChatMessage) {
        call { _, _ ->
            val f = mediaFile(m) ?: throw ApiException("Не удалось загрузить файл")
            VoicePlayer.toggle(m.id, f)
        }
    }

    fun openVideo(m: ChatMessage) {
        VoicePlayer.stop()
        call { _, _ ->
            val f = mediaFile(m) ?: throw ApiException("Не удалось загрузить видео")
            _state.update { it.copy(video = f) }
        }
    }

    fun closeVideo() = _state.update { it.copy(video = null) }

    // ---------- комнаты ----------

    fun createRoom(name: String, kind: String = "group") = call { c, t ->
        val room = c.createRoom(t, name, kind)
        reloadRooms(c, t)
        openRoom(room.id)
    }

    fun openDm(username: String) = call { c, t ->
        val room = c.openDm(t, username)
        reloadRooms(c, t)
        openRoom(room.id)
    }

    fun joinRoom(id: Long) = call { c, t ->
        val room = c.joinRoom(t, id)
        reloadRooms(c, t)
        _state.update { it.copy(discover = null) }
        openRoom(room.id)
    }

    fun loadDiscover() = call { c, t ->
        val list = c.discover(t)
        _state.update { it.copy(discover = list) }
    }

    fun dismissDiscover() = _state.update { it.copy(discover = null) }
    fun clearError() = _state.update { it.copy(error = null) }
    fun showError(message: String?) = _state.update { it.copy(error = message) }

    // ---------- звонки ----------

    /** Разрешения (микрофон, для видео ещё камера) должен получить UI до вызова. */
    fun startCall(roomId: Long, video: Boolean) {
        val s = _state.value
        if (s.call != null) return
        if (!s.connected) { showError("Нет соединения с сервером"); return }
        val room = s.rooms.firstOrNull { it.id == roomId } ?: return
        val id = UUID.randomUUID().toString()
        VoicePlayer.stop()
        _state.update {
            it.copy(call = CallUi(id, roomId, room.name, video, outgoing = true, group = !room.isDirect,
                phase = CallPhase.RINGING, speaker = video))
        }
        CallService.start(getApplication<Application>(), video)
        call { c, t ->
            try {
                callManager.prepareLocal(video, c.iceServers(t))
                sendJson(JSONObject().put("type", "call_invite").put("room_id", roomId)
                    .put("call_id", id).put("video", video))
            } catch (e: Exception) {
                cleanupCall(null)
                throw e
            }
        }
    }

    fun acceptCall() {
        val c0 = _state.value.call ?: return
        if (c0.phase != CallPhase.INCOMING) return
        _state.update { it.copy(call = c0.copy(phase = CallPhase.CONNECTING, speaker = c0.video)) }
        CallService.start(getApplication<Application>(), c0.video)
        call { c, t ->
            try {
                callManager.prepareLocal(c0.video, c.iceServers(t))
                sendJson(JSONObject().put("type", "call_accept").put("call_id", c0.callId))
            } catch (e: Exception) {
                hangUp(null)
                throw e
            }
        }
    }

    /** Завершить звонок или отклонить входящий. */
    fun hangUp(message: String? = null) {
        val c = _state.value.call ?: return
        val type = if (c.phase == CallPhase.INCOMING) "call_reject" else "call_end"
        sendJson(JSONObject().put("type", type).put("call_id", c.callId))
        cleanupCall(message)
    }

    fun toggleMute() {
        val c = _state.value.call ?: return
        callManager.setMuted(!c.muted)
        _state.update { it.copy(call = c.copy(muted = !c.muted)) }
    }

    fun toggleCamera() {
        val c = _state.value.call ?: return
        callManager.setCameraOff(!c.cameraOff)
        _state.update { it.copy(call = c.copy(cameraOff = !c.cameraOff)) }
    }

    fun toggleSpeaker() {
        val c = _state.value.call ?: return
        callManager.setSpeaker(!c.speaker)
        _state.update { it.copy(call = c.copy(speaker = !c.speaker)) }
    }

    fun switchCamera() = callManager.switchCamera()

    /** Аудио-фокус отняла другая система (например, ответили на обычный звонок по SIM) — глушим себя и
        встаём «на удержание»; когда фокус вернётся, восстанавливаем микрофон в прежнее состояние. */
    private var mutedBeforeHold = false
    private fun onCallHoldChanged(held: Boolean) {
        val c = _state.value.call ?: return
        if (held) {
            if (c.onHold) return
            mutedBeforeHold = c.muted
            callManager.setMuted(true)
            _state.update { it.copy(call = it.call?.copy(onHold = true, muted = true)) }
        } else {
            if (!c.onHold) return
            callManager.setMuted(mutedBeforeHold)
            _state.update { it.copy(call = it.call?.copy(onHold = false, muted = mutedBeforeHold)) }
        }
    }

    private fun onCallInvite(o: JSONObject) {
        if (_state.value.call != null) return
        val call = CallUi(
            callId = o.getString("call_id"), roomId = o.getLong("room_id"), roomName = o.getString("from"),
            video = o.optBoolean("video"), outgoing = false, group = o.optBoolean("group"), phase = CallPhase.INCOMING,
        )
        VoicePlayer.stop()
        _state.update { it.copy(call = call) }
    }

    /** Меня только что приняли в звонок — соединяюсь с каждым, кто уже там. Новичок (то есть я) всегда сам
        предлагает оффер уже подключённым — так у обеих сторон однозначно, чья очередь звонить (см. §11 в README). */
    private fun onCallJoined(o: JSONObject) {
        val c = _state.value.call ?: return
        if (c.callId != o.getString("call_id")) return
        if (c.phase == CallPhase.INCOMING) { cleanupCall(null); return }   // приняли на другом устройстве
        val arr = o.getJSONArray("participants")
        val joined = mutableMapOf<Long, String>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            joined[p.getLong("id")] = p.getString("username")
        }
        _state.update { it.copy(call = it.call?.copy(phase = CallPhase.CONNECTING, participants = it.call.participants + joined)) }
        for (peerId in joined.keys) {
            callManager.addPeer(peerId)
            callManager.createOffer(peerId)
        }
    }

    /** Кто-то ещё присоединился, пока я уже в звонке — готовлю приёмное соединение и жду от него оффер. */
    private fun onCallPeerJoined(o: JSONObject) {
        val c = _state.value.call ?: return
        if (c.callId != o.getString("call_id")) return
        val peerId = o.getLong("user_id"); val username = o.getString("username")
        _state.update { it.copy(call = it.call?.copy(participants = it.call.participants + (peerId to username))) }
        callManager.addPeer(peerId)
    }

    private fun onCallPeerLeft(o: JSONObject) {
        val c = _state.value.call ?: return
        if (c.callId != o.getString("call_id")) return
        val peerId = o.getLong("user_id")
        callManager.removePeer(peerId)
        _state.update { it.copy(call = it.call?.copy(participants = it.call.participants - peerId)) }
    }

    private fun onCallEnd(id: String, reason: String) {
        if (_state.value.call?.callId != id) return
        cleanupCall(
            when (reason) {
                "busy" -> "Абонент занят"
                "declined" -> "Звонок отклонён"
                "no_answer" -> "Нет ответа"
                "connection_lost" -> "Соединение потеряно"
                else -> null
            }
        )
    }

    /** Звонок становится «активным», как только соединилось хотя бы с одним участником. */
    private fun markPeerConnected(peerId: Long) {
        _state.update { st ->
            val c = st.call ?: return@update st
            st.copy(call = c.copy(phase = CallPhase.ACTIVE, startedAt = c.startedAt ?: System.currentTimeMillis()))
        }
    }

    /** Одно упавшее соединение не должно рушить весь звонок, если остаются другие участники. */
    private fun onPeerConnectionFailed(peerId: Long) {
        val c = _state.value.call ?: return
        if (c.participants.size <= 1) {
            hangUp("Не удалось соединиться")
        } else {
            callManager.removePeer(peerId)
            _state.update { it.copy(call = it.call?.copy(participants = it.call.participants - peerId)) }
        }
    }

    private fun cleanupCall(message: String?) {
        if (callLazy.isInitialized()) callManager.close()
        CallService.stop(getApplication<Application>())
        _state.update { it.copy(call = null) }
        message?.let { showError(it) }
        if (!foreground) disconnect() // приложение свёрнуто, звонок закончился — сокет больше не нужен
    }

    // ---------- шифрование ----------

    private fun locked(raw: RawMessage, code: String) = ChatMessage(
        raw.id, raw.roomId, raw.username, LOCKED_TEXT[code] ?: LOCKED_TEXT.getValue("bad_data"), "locked", null, null, raw.ts,
    )

    /** Превращает запись сервера в готовое к показу сообщение; при сбое — заглушка «не удалось расшифровать». */
    private suspend fun openMsg(raw: RawMessage): ChatMessage {
        if (raw.kind == "call") {
            return ChatMessage(raw.id, raw.roomId, raw.username, raw.text, "call", null, null, raw.ts)
        }
        val decodedReactions = decryptReactions(raw.roomId, raw.reactions)
        val decodedVotes = decryptVotes(raw.roomId, raw.votes)
        val e = e2ee ?: return locked(raw, "bad_data").copy(reactionsDecoded = decodedReactions, pinned = raw.pinned, votes = raw.votes, votesDecoded = decodedVotes)
        return try {
            val p = e.decryptMessage(raw)
            val f = p.optJSONObject("file")
            val rt = p.optJSONObject("reply_to")
            val fwd = p.optJSONObject("forwarded_from")
            val optionsArr = p.optJSONArray("options")
            ChatMessage(
                id = raw.id, roomId = raw.roomId, username = raw.username,
                text = if (p.has("text") && !p.isNull("text")) p.getString("text") else "",
                kind = p.optString("t", "text").takeIf { it in setOf("text", "image", "audio", "video", "file", "poll") } ?: "text",
                mediaPath = raw.mediaPath,
                duration = if (p.has("dur") && !p.isNull("dur")) p.getDouble("dur") else null,
                ts = raw.ts,
                file = f?.let { FileInfo(it.getString("k"), it.getString("n"), it.optLong("size"), it.optString("mime")) },
                fileName = if (p.has("name") && !p.isNull("name")) p.getString("name") else null,
                replyTo = rt?.let { ReplyRef(it.getLong("id"), it.getString("username"), it.optString("t", "text"), it.optString("text", "")) },
                editedTs = raw.editedTs,
                reactions = raw.reactions,
                reactionsDecoded = decodedReactions,
                pinned = raw.pinned,
                forwardedFrom = fwd?.optString("username"),
                votes = raw.votes,
                votesDecoded = decodedVotes,
                question = if (p.has("question") && !p.isNull("question")) p.getString("question") else null,
                options = optionsArr?.let { arr -> List(arr.length()) { arr.getString(it) } },
            )
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: E2eeException) {
            locked(raw, ex.code).copy(reactionsDecoded = decodedReactions, pinned = raw.pinned, votes = raw.votes, votesDecoded = decodedVotes)
        } catch (ex: ApiException) {
            locked(raw, "bad_data").copy(reactionsDecoded = decodedReactions, pinned = raw.pinned, votes = raw.votes, votesDecoded = decodedVotes)
        } catch (ex: JSONException) {
            locked(raw, "bad_data").copy(reactionsDecoded = decodedReactions, pinned = raw.pinned, votes = raw.votes, votesDecoded = decodedVotes)
        }
    }

    /** Каждая реакция — своё маленькое зашифрованное сообщение (тот же формат, что и обычные). */
    private suspend fun decryptReactions(roomId: Long, reactions: List<RawReaction>): List<DecodedReaction> {
        if (reactions.isEmpty()) return emptyList()
        val e = e2ee ?: return emptyList()
        val out = ArrayList<DecodedReaction>()
        for (r in reactions) {
            try {
                val synthetic = RawMessage(0, roomId, r.username, "e2e", "", r.epoch, r.ct, r.sig, null, 0.0)
                val p = e.decryptMessage(synthetic)
                out.add(DecodedReaction(r.username, p.getString("emoji")))
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) { /* не смогли — просто не покажем эту реакцию */ }
        }
        return out
    }

    /** Голоса опроса — той же формы, что реакции: каждый отдельно зашифрован ключом комнаты. */
    private suspend fun decryptVotes(roomId: Long, votes: List<RawVote>): List<DecodedVote> {
        if (votes.isEmpty()) return emptyList()
        val e = e2ee ?: return emptyList()
        val out = ArrayList<DecodedVote>()
        for (v in votes) {
            try {
                val synthetic = RawMessage(0, roomId, v.username, "e2e", "", v.epoch, v.ct, v.sig, null, 0.0)
                val p = e.decryptMessage(synthetic)
                out.add(DecodedVote(v.username, p.getInt("option")))
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) { /* не смогли — просто не покажем этот голос */ }
        }
        return out
    }

    private suspend fun decryptAll(list: List<RawMessage>): List<ChatMessage> = list.map { openMsg(it) }

    /** Комнаты с превью, расшифрованным на устройстве (сервер отдаёт последнее сообщение шифртекстом). */
    private suspend fun loadRoomsDecrypted(c: ApiClient, t: String): List<Room> {
        val rooms = c.rooms(t)
        val e = e2ee ?: return rooms
        try {
            e.fetchKeys(null) // все конверты с ключами одним запросом
        } catch (ex: ApiException) {
            if (ex.code == 401) throw ex
        } catch (ex: E2eeException) {
            // например, key_changed: подробности появятся при открытии комнаты
        }
        for (r in rooms) e.noteEpoch(r.id, r.epoch)
        return rooms.map { r ->
            val last = r.last ?: return@map r
            val m = openMsg(last)
            r.copy(lastPreview = previewOf(r.isDirect, m.username, m.text, m.kind, m.fileName))
        }
    }

    private suspend fun processQueued(o: JSONObject) {
        when (o.optString("type")) {
            "message" -> handleMessage(openMsg(parseRaw(o)))
            "message_edited" -> {
                val roomId = o.getLong("room_id"); val id = o.getLong("id")
                val existing = _state.value.messages[roomId]?.firstOrNull { it.id == id } ?: return
                val raw = RawMessage(
                    id = id, roomId = roomId, username = existing.username, kind = "e2e", text = "",
                    epoch = o.getInt("epoch"), ct = o.getString("ct"), sig = o.getString("sig"),
                    mediaPath = existing.mediaPath, ts = existing.ts, editedTs = o.getDouble("edited_ts"),
                    reactions = existing.reactions, pinned = existing.pinned,
                )
                applyEdit(openMsg(raw))
            }
            "message_deleted" -> applyDelete(o.getLong("room_id"), o.getLong("id"))
            "reaction_set" -> applyReactionSet(o)
            "reaction_removed" -> applyReactionRemoved(o)
            "vote_set" -> applyVoteSet(o)
            "vote_removed" -> applyVoteRemoved(o)
            "message_pinned" -> applyPinned(o.getLong("room_id"), o.getLong("message_id"), true)
            "message_unpinned" -> applyPinned(o.getLong("room_id"), o.getLong("message_id"), false)
            "autodelete_changed" -> {
                val roomId = o.getLong("room_id")
                val seconds = if (o.isNull("seconds")) null else o.getInt("seconds")
                _state.update { s -> s.copy(rooms = s.rooms.map { if (it.id == roomId) it.copy(autodeleteSeconds = seconds) else it }) }
            }
            "read_state" -> {
                val roomId = o.getLong("room_id"); val username = o.getString("username"); val msgId = o.getLong("message_id")
                _state.update { s ->
                    val forRoom = (s.readState[roomId] ?: emptyMap()) + (username to maxOf(s.readState[roomId]?.get(username) ?: 0L, msgId))
                    s.copy(readState = s.readState + (roomId to forRoom))
                }
            }
            "rooms_changed" -> {
                val c = api ?: return
                val t = _state.value.session?.token ?: return
                reloadRooms(c, t)
            }
            "keys_updated" -> { // новая эпоха ключа: забираем её раньше, чем придут сообщения в ней
                val room = o.getLong("room_id")
                val epoch = o.getInt("epoch")
                val e = e2ee ?: return
                e.noteEpoch(room, epoch)
                _state.update { s -> s.copy(rooms = s.rooms.map { if (it.id == room) it.copy(epoch = epoch) else it }) }
                try { e.fetchKeys(room) } catch (ex: ApiException) { } catch (ex: E2eeException) { }
            }
        }
    }

    /** Реакция (своя или чужая) добавлена/изменена — и в ответ на свой PUT, и по WS от других. */
    private suspend fun applyReactionSet(ev: JSONObject) {
        val roomId = ev.getLong("room_id"); val msgId = ev.getLong("message_id")
        val list = _state.value.messages[roomId] ?: return
        val m = list.firstOrNull { it.id == msgId } ?: return
        val raw = RawReaction(ev.getString("username"), ev.getInt("epoch"), ev.getString("ct"), ev.getString("sig"))
        val reactions = m.reactions.filter { it.username != raw.username } + raw
        val decoded = decryptReactions(roomId, reactions)
        val updated = m.copy(reactions = reactions, reactionsDecoded = decoded)
        _state.update { s -> s.copy(messages = s.messages + (roomId to (s.messages[roomId] ?: emptyList()).map { if (it.id == msgId) updated else it })) }
    }

    private fun applyReactionRemoved(ev: JSONObject) {
        val roomId = ev.getLong("room_id"); val msgId = ev.getLong("message_id"); val username = ev.getString("username")
        val list = _state.value.messages[roomId] ?: return
        val m = list.firstOrNull { it.id == msgId } ?: return
        val updated = m.copy(
            reactions = m.reactions.filter { it.username != username },
            reactionsDecoded = m.reactionsDecoded.filter { it.username != username },
        )
        _state.update { s -> s.copy(messages = s.messages + (roomId to (s.messages[roomId] ?: emptyList()).map { if (it.id == msgId) updated else it })) }
    }

    private suspend fun applyVoteSet(ev: JSONObject) {
        val roomId = ev.getLong("room_id"); val msgId = ev.getLong("message_id")
        val list = _state.value.messages[roomId] ?: return
        val m = list.firstOrNull { it.id == msgId } ?: return
        val raw = RawVote(ev.getString("username"), ev.getInt("epoch"), ev.getString("ct"), ev.getString("sig"))
        val votes = m.votes.filter { it.username != raw.username } + raw
        val decoded = decryptVotes(roomId, votes)
        val updated = m.copy(votes = votes, votesDecoded = decoded)
        _state.update { s -> s.copy(messages = s.messages + (roomId to (s.messages[roomId] ?: emptyList()).map { if (it.id == msgId) updated else it })) }
    }

    private fun applyVoteRemoved(ev: JSONObject) {
        val roomId = ev.getLong("room_id"); val msgId = ev.getLong("message_id"); val username = ev.getString("username")
        val list = _state.value.messages[roomId] ?: return
        val m = list.firstOrNull { it.id == msgId } ?: return
        val updated = m.copy(
            votes = m.votes.filter { it.username != username },
            votesDecoded = m.votesDecoded.filter { it.username != username },
        )
        _state.update { s -> s.copy(messages = s.messages + (roomId to (s.messages[roomId] ?: emptyList()).map { if (it.id == msgId) updated else it })) }
    }

    /** Тап по своему же варианту снимает голос — опрос одиночного выбора, переголосовать «никак иначе» нельзя. */
    fun toggleVote(m: ChatMessage, option: Int) {
        val mine = m.votesDecoded.firstOrNull { it.username == _state.value.session?.username }
        if (mine?.option == option) removeVote(m) else setVote(m, option)
    }

    private fun setVote(m: ChatMessage, option: Int) {
        val e = e2ee ?: return
        call { c, t ->
            val sealed = e.seal(m.roomId, JSONObject().put("v", 1).put("option", option))
            c.setVote(t, m.roomId, m.id, sealed.epoch, sealed.ct, sealed.sig)
            applyVoteSet(JSONObject().put("room_id", m.roomId).put("message_id", m.id)
                .put("username", _state.value.session?.username).put("epoch", sealed.epoch)
                .put("ct", sealed.ct).put("sig", sealed.sig))
        }
    }

    private fun removeVote(m: ChatMessage) {
        call { c, t ->
            c.removeVote(t, m.roomId, m.id)
            applyVoteRemoved(JSONObject().put("room_id", m.roomId).put("message_id", m.id)
                .put("username", _state.value.session?.username))
        }
    }

    /** Опрос — не отдельная сущность, а обычное сообщение с t:"poll" внутри зашифрованного payload. */
    fun createPoll(roomId: Long, question: String, options: List<String>) {
        val e = e2ee ?: return
        call { c, t ->
            val optArr = org.json.JSONArray(); options.forEach { optArr.put(it) }
            val payload = JSONObject().put("v", 1).put("t", "poll").put("question", question).put("options", optArr)
            val sealed = e.seal(roomId, payload)
            val ok = socket?.send(
                JSONObject().put("type", "message").put("room_id", roomId)
                    .put("epoch", sealed.epoch).put("ct", sealed.ct).put("sig", sealed.sig).toString()
            ) == true
            if (!ok) showError("Нет соединения с сервером")
        }
    }

    // ---------- отложенная отправка (только текст — см. README) ----------

    fun refreshScheduled(roomId: Long) {
        call { c, t ->
            val list = c.listScheduled(t, roomId)
            _state.update { s -> s.copy(scheduled = s.scheduled + (roomId to list)) }
        }
    }

    fun scheduleMessage(roomId: Long, text: String, sendAtSeconds: Double, onDone: () -> Unit) {
        val e = e2ee ?: return
        call { c, t ->
            val sealed = e.seal(roomId, JSONObject().put("v", 1).put("t", "text").put("text", text))
            c.scheduleMessage(t, roomId, sealed.epoch, sealed.ct, sealed.sig, sendAtSeconds)
            onDone()
            refreshScheduled(roomId)
        }
    }

    fun cancelScheduled(roomId: Long, scheduledId: Long) {
        call { c, t ->
            c.cancelScheduled(t, roomId, scheduledId)
            refreshScheduled(roomId)
        }
    }

    // ---------- каталог стикеров (открытый, не E2EE) ----------

    fun toggleStickerTray() {
        val open = !_state.value.stickerTrayOpen
        _state.update { it.copy(stickerTrayOpen = open) }
        if (open) refreshInstalledStickers()
    }

    fun closeStickerTray() { _state.update { it.copy(stickerTrayOpen = false) } }

    private fun refreshInstalledStickers() {
        call { c, t -> val list = c.installedStickerPacks(t); _state.update { it.copy(installedStickerPacks = list) } }
    }

    /** Стикер отправляется как обычное зашифрованное фото — тот же путь, что и для остальных изображений;
        каталог сам по себе никак не шифруется (см. README §12 и E2EE.md). */
    fun sendSticker(roomId: Long, sticker: Sticker) {
        call { c, t ->
            val app = getApplication<Application>()
            val dst = File(app.cacheDir, "stk_${System.nanoTime()}.jpg")
            try {
                c.download(t, sticker.url, dst)
            } catch (ex: ApiException) {
                dst.delete(); showError("Не удалось скачать стикер"); return@call
            }
            val mime = when {
                sticker.url.endsWith(".png") -> "image/png"
                sticker.url.endsWith(".gif") -> "image/gif"
                sticker.url.endsWith(".webp") -> "image/webp"
                else -> "image/jpeg"
            }
            uploadEncrypted(c, t, roomId, "image", dst, mime, null)
            closeStickerTray()
        }
    }

    fun openStickerCatalog() {
        _state.update { it.copy(stickerCatalog = emptyList(), stickerCatalogQuery = "") }
        searchStickerPacks("")
    }

    fun closeStickerCatalog() { _state.update { it.copy(stickerCatalog = null) } }

    fun searchStickerPacks(query: String) {
        _state.update { it.copy(stickerCatalogQuery = query) }
        call { c, t -> val list = c.searchStickerPacks(t, query); _state.update { it.copy(stickerCatalog = list) } }
    }

    fun openStickerPackDetail(packId: Long) {
        call { c, t -> val pack = c.getStickerPack(t, packId); _state.update { it.copy(stickerPackDetail = pack) } }
    }

    fun closeStickerPackDetail() { _state.update { it.copy(stickerPackDetail = null) } }

    fun createStickerPack(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) { showError("Введите название"); return }
        call { c, t ->
            val pack = c.createStickerPack(t, trimmed)
            openStickerPackDetail(pack.id)
            searchStickerPacks(_state.value.stickerCatalogQuery)
        }
    }

    fun toggleInstallPack(pack: StickerPack) {
        call { c, t ->
            if (pack.installed) c.uninstallStickerPack(t, pack.id) else c.installStickerPack(t, pack.id)
            openStickerPackDetail(pack.id)
            refreshInstalledStickers()
        }
    }

    fun deleteStickerPackAction(packId: Long) {
        call { c, t ->
            c.deleteStickerPack(t, packId)
            closeStickerPackDetail()
            searchStickerPacks(_state.value.stickerCatalogQuery)
            refreshInstalledStickers()
        }
    }

    fun addStickerToPack(packId: Long, uri: Uri) {
        call { c, t ->
            val app = getApplication<Application>()
            val bytes = withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            if (bytes == null) { showError("Не удалось прочитать файл"); return@call }
            c.addSticker(t, packId, bytes.toRequestBody("image/*".toMediaType()))
            openStickerPackDetail(packId)
        }
    }

    fun deleteStickerFromPack(packId: Long, stickerId: Long) {
        call { c, t -> c.deleteSticker(t, packId, stickerId); openStickerPackDetail(packId) }
    }

    private fun applyPinned(roomId: Long, msgId: Long, pinned: Boolean) {
        val list = _state.value.messages[roomId] ?: return
        val target = list.firstOrNull { it.id == msgId }
        val updated = list.map { if (it.id == msgId) it.copy(pinned = pinned) else it }
        _state.update { s ->
            val bar = s.pinnedBar[roomId] ?: emptyList()
            val newBar = if (pinned) {
                if (target != null && bar.none { it.id == msgId }) bar + target.copy(pinned = true) else bar
            } else {
                bar.filter { it.id != msgId }
            }
            s.copy(messages = s.messages + (roomId to updated), pinnedBar = s.pinnedBar + (roomId to newBar))
        }
    }

    /** Сообщение отредактировано (свой ответ на PUT или чужое message_edited по WS). */
    private fun applyEdit(m: ChatMessage) {
        _state.update { s ->
            val list = s.messages[m.roomId] ?: return@update s
            val wasLast = list.lastOrNull()?.id == m.id
            val rooms = if (!wasLast) s.rooms else s.rooms.map { r ->
                if (r.id == m.roomId) r.copy(lastPreview = previewOf(r.isDirect, m.username, m.text, m.kind, m.fileName)) else r
            }
            s.copy(messages = s.messages + (m.roomId to list.map { if (it.id == m.id) m else it }), rooms = rooms)
        }
    }

    /** «У себя» — только у инициатора; «у всех» — всем по WS message_deleted. И то и другое приходит сюда. */
    private fun applyDelete(roomId: Long, id: Long) {
        _state.update { s ->
            val list = s.messages[roomId] ?: return@update s
            val wasLast = list.lastOrNull()?.id == id
            val updated = list.filter { it.id != id }
            val rooms = if (!wasLast) s.rooms else {
                val last = updated.lastOrNull()
                s.rooms.map { r ->
                    if (r.id != roomId) r
                    else r.copy(lastPreview = last?.let { previewOf(r.isDirect, it.username, it.text, it.kind, it.fileName) } ?: "")
                }
            }
            s.copy(messages = s.messages + (roomId to updated), rooms = rooms)
        }
    }

    fun editMessage(roomId: Long, messageId: Long, text: String, onDone: () -> Unit) {
        val e = e2ee ?: return
        viewModelScope.launch {
            try {
                val sealed = e.seal(roomId, JSONObject().put("v", 1).put("t", "text").put("text", text))
                val c = api ?: return@launch
                val t = _state.value.session?.token ?: return@launch
                val r = c.editMessage(t, roomId, messageId, sealed.epoch, sealed.ct, sealed.sig)
                val existing = _state.value.messages[roomId]?.firstOrNull { it.id == messageId }
                if (existing != null) {
                    val raw = RawMessage(messageId, roomId, existing.username, "e2e", "", r.epoch, r.ct, r.sig, existing.mediaPath, existing.ts, r.editedTs)
                    applyEdit(openMsg(raw))
                }
                cancelEdit()
                onDone()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: ApiException) {
                showError(ex.message)
            } catch (ex: E2eeException) {
                showError(ex.message)
            }
        }
    }

    fun deleteMessage(m: ChatMessage, forEveryone: Boolean) {
        call { c, t ->
            c.deleteMessage(t, m.roomId, m.id, forEveryone)
            applyDelete(m.roomId, m.id)
        }
    }

    // ---------- реакции, закрепление, самоудаление, пересылка ----------

    fun currentUsername(): String? = _state.value.session?.username

    /** Для автодополнения @упоминаний в composer'е — только в групповых чатах. */
    suspend fun fetchRoomMembers(roomId: Long): List<String> {
        val c = api ?: return emptyList()
        val t = _state.value.session?.token ?: return emptyList()
        return try {
            c.members(t, roomId).map { it.username }.filter { it != currentUsername() }
        } catch (ex: Exception) {
            emptyList()
        }
    }

    fun toggleReaction(m: ChatMessage, emoji: String) {
        val mine = m.reactionsDecoded.firstOrNull { it.username == _state.value.session?.username }
        if (mine?.emoji == emoji) removeReaction(m) else setReaction(m, emoji)
    }

    private fun setReaction(m: ChatMessage, emoji: String) {
        val e = e2ee ?: return
        call { c, t ->
            val sealed = e.seal(m.roomId, JSONObject().put("v", 1).put("emoji", emoji))
            c.setReaction(t, m.roomId, m.id, sealed.epoch, sealed.ct, sealed.sig)
            applyReactionSet(JSONObject().put("room_id", m.roomId).put("message_id", m.id)
                .put("username", _state.value.session?.username).put("epoch", sealed.epoch)
                .put("ct", sealed.ct).put("sig", sealed.sig))
        }
    }

    private fun removeReaction(m: ChatMessage) {
        call { c, t ->
            c.removeReaction(t, m.roomId, m.id)
            applyReactionRemoved(JSONObject().put("room_id", m.roomId).put("message_id", m.id)
                .put("username", _state.value.session?.username))
        }
    }

    fun pinMessage(m: ChatMessage) = call { c, t -> c.pinMessage(t, m.roomId, m.id); applyPinned(m.roomId, m.id, true) }
    fun unpinMessage(m: ChatMessage) = call { c, t -> c.unpinMessage(t, m.roomId, m.id); applyPinned(m.roomId, m.id, false) }

    fun setAutodelete(roomId: Long, seconds: Int?) {
        call { c, t ->
            c.setAutodelete(t, roomId, seconds)
            _state.update { s -> s.copy(rooms = s.rooms.map { if (it.id == roomId) it.copy(autodeleteSeconds = seconds) else it }) }
        }
    }

    /** Текст — как новое зашифрованное сообщение в комнате-адресате; файл — расшифровывается на устройстве
        (тот же кэш, что и для показа/воспроизведения) и заново шифруется под ключ другой комнаты. Отдельного
        «скопировать файл на сервере» эндпоинта нет и не нужно — шифрование и так только на клиенте. */
    fun forwardMessage(m: ChatMessage, targetRoomId: Long) {
        val e = e2ee ?: return
        call { c, t ->
            val from = JSONObject().put("username", m.username)
            if (m.kind == "text") {
                val payload = JSONObject().put("v", 1).put("t", "text").put("text", m.text).put("forwarded_from", from)
                val sealed = e.seal(targetRoomId, payload)
                val ok = socket?.send(
                    JSONObject().put("type", "message").put("room_id", targetRoomId)
                        .put("epoch", sealed.epoch).put("ct", sealed.ct).put("sig", sealed.sig).toString()
                ) == true
                if (!ok) showError("Нет соединения с сервером")
            } else {
                val src = mediaFile(m) ?: run { showError("Не удалось загрузить файл"); return@call }
                val app = getApplication<Application>()
                val enc = File(app.cacheDir, "fwd_${System.nanoTime()}.enc")
                try {
                    val secret = withContext(Dispatchers.IO) { e.encryptFile(src, enc) }
                    val payload = JSONObject().put("v", 1).put("t", m.kind).put("forwarded_from", from)
                    if (m.duration != null) payload.put("dur", m.duration)
                    if (m.fileName != null) payload.put("name", m.fileName)
                    payload.put("file", JSONObject().put("k", secret.key).put("n", secret.nonce)
                        .put("size", secret.size).put("mime", m.file?.mime ?: ""))
                    val sealed = e.seal(targetRoomId, payload)
                    val raw = c.uploadMedia(t, targetRoomId, sealed.epoch, sealed.ct, sealed.sig,
                        enc.asRequestBody("application/octet-stream".toMediaType()))
                    handleMessage(openMsg(raw))
                } finally {
                    enc.delete()
                }
            }
        }
    }

    private fun onServerError(o: JSONObject) {
        val room = o.optLong("room_id", -1)
        val e = e2ee
        if (o.optString("code") == "stale_epoch" && room >= 0 && e != null) {
            viewModelScope.launch {
                try { e.fetchKeys(room) } catch (ex: ApiException) { } catch (ex: E2eeException) { }
            }
        }
        showError(o.optString("message"))
    }

    private suspend fun sendSealedSignal(callId: String, peerId: Long, payload: JSONObject) {
        val c = _state.value.call?.takeIf { it.callId == callId } ?: return
        val username = c.participants[peerId] ?: return
        val e = e2ee ?: return
        try {
            val sealed = e.sealSignal(username, callId, payload)
            sendJson(JSONObject().put("type", "call_signal").put("call_id", callId).put("to", peerId).put("data", sealed))
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            hangUp("Ошибка шифрования звонка")
        }
    }

    private suspend fun openIncomingSignal(callId: String, peerId: Long, fromUsername: String, data: JSONObject) {
        if (_state.value.call?.callId != callId) return
        val e = e2ee ?: return
        try {
            callManager.onRemoteSignal(peerId, e.openSignal(fromUsername, callId, data))
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            hangUp("Не удалось проверить безопасность звонка")
        }
    }

    /** Окно «Безопасность»: номер безопасности для личного чата, сведения о ключе для комнаты. */
    fun showSecurity(roomId: Long) {
        val room = _state.value.rooms.firstOrNull { it.id == roomId } ?: return
        val e = e2ee ?: return
        viewModelScope.launch {
            try {
                val info = if (room.isDirect) {
                    val peer = room.peer ?: room.name
                    SecurityInfo(
                        "Сквозное шифрование",
                        "Сообщения, фото, голосовые, видео и звонки с $peer зашифрованы: их не может прочитать даже сервер." +
                            "\n\nСравните номер безопасности с собеседником лично или по другому каналу. " +
                            "Если номера совпадают, никто не подменил ключи.",
                        e.safetyNumber(peer),
                    )
                } else {
                    SecurityInfo(
                        "Сквозное шифрование",
                        "Сообщения комнаты зашифрованы общим ключом, который получают только её участники. " +
                            "Текущая эпоха ключа: ${room.epoch}. Новый участник получает новый ключ и не читает " +
                            "сообщения, отправленные до его вступления." +
                            "\n\nПубличная комната: любой, кто в неё вступил, читает её сообщения. " +
                            "Чтобы проверить конкретного человека, откройте личный чат с ним.",
                        null,
                    )
                }
                _state.update { it.copy(security = info) }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: ApiException) {
                showError(ex.message)
            } catch (ex: E2eeException) {
                showError(ex.message)
            }
        }
    }

    fun dismissSecurity() = _state.update { it.copy(security = null) }

    // ---------- вспомогательное ----------

    private fun refreshRooms() = call { c, t -> reloadRooms(c, t) }

    private suspend fun reloadRooms(c: ApiClient, t: String) {
        val rooms = loadRoomsDecrypted(c, t)
        _state.update { s ->
            s.copy(rooms = rooms.map { if (it.id == s.currentRoomId) it.copy(unread = 0) else it })
        }
    }

    private fun addMessages(roomId: Long, list: List<ChatMessage>) {
        _state.update { s ->
            val merged = (s.messages[roomId].orEmpty() + list).distinctBy { it.id }.sortedBy { it.id }
            s.copy(messages = s.messages + (roomId to merged))
        }
    }

    private fun sendRead(roomId: Long, messageId: Long) {
        socket?.send(
            JSONObject().put("type", "read").put("room_id", roomId).put("message_id", messageId).toString()
        )
    }

    /** Запускает сетевую операцию с единой обработкой ошибок. */
    private fun call(block: suspend (ApiClient, String) -> Unit) {
        val client = api ?: return
        val token = _state.value.session?.token ?: return
        viewModelScope.launch {
            try {
                block(client, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.code == 401) logout() else showError(e.message)
            } catch (e: Exception) {
                showError("Ошибка: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        generation++
        socket?.cancel()
        VoicePlayer.stop()
        if (callLazy.isInitialized()) callManager.destroy()
        CallService.stop(getApplication<Application>())
    }
}
