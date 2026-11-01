package com.example.chat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Сквозное шифрование Freedom (протокол FRDM-E2EE v1, см. E2EE.md).
 * Сервер видит только шифртекст, публичные ключи и метаданные. Реализация совместима
 * побайтно с веб-клиентом (e2ee.js): одни и те же строки-домены, форматы и порядок полей.
 */

/** code: no_key | bad_sig | key_changed | bad_data | bad_blob */
class E2eeException(val code: String, message: String? = null) : Exception(message ?: code)

class Identity(val dhPriv: PrivateKey, val dhSpki: ByteArray, val sigPriv: PrivateKey, val sigSpki: ByteArray)
class SealedMsg(val epoch: Int, val ct: String, val sig: String)
class FileSecret(val key: String, val nonce: String, val size: Long)

/** Ключи из пароля. auth уходит на сервер как «пароль», kek остаётся на устройстве. */
object Onboarding {
    const val ITERATIONS = 310_000

    class Secrets(val auth: String, val kek: ByteArray)

    fun deriveSecrets(username: String, password: String): Secrets {
        val master = Crypto.pbkdf2(password, Crypto.u("freedom-e2ee-v1:$username"), ITERATIONS)
        return Secrets(Crypto.b64(Crypto.hkdf(master, "auth")), Crypto.hkdf(master, "kek"))
    }

    fun createIdentity(): Identity {
        val dh = Crypto.generateEc()
        val sg = Crypto.generateEc()
        return Identity(dh.private, dh.public.encoded, sg.private, sg.public.encoded)
    }

    /** Приватные ключи, зашифрованные kek. Именно этот блоб хранит сервер. */
    fun sealBlob(id: Identity, kek: ByteArray, username: String): String {
        val plain = JSONObject()
            .put("dh", Crypto.b64(id.dhPriv.encoded)).put("dhPub", Crypto.b64(id.dhSpki))
            .put("sig", Crypto.b64(id.sigPriv.encoded)).put("sigPub", Crypto.b64(id.sigSpki))
            .toString()
        val n = Crypto.random(12)
        val c = Crypto.gcmEncrypt(kek, n, Crypto.u(plain), Crypto.u("FRDM-KEYBLOB-v1|$username"))
        return JSONObject().put("v", 1).put("n", Crypto.b64(n)).put("c", Crypto.b64(c)).toString()
    }

    fun openBlob(blob: String, kek: ByteArray, username: String): Identity {
        try {
            val o = JSONObject(blob)
            val plain = Crypto.gcmDecrypt(
                kek, Crypto.unb64(o.getString("n")), Crypto.unb64(o.getString("c")),
                Crypto.u("FRDM-KEYBLOB-v1|$username"),
            )
            val p = JSONObject(String(plain, Charsets.UTF_8))
            return Identity(
                Crypto.privateKey(Crypto.unb64(p.getString("dh"))), Crypto.unb64(p.getString("dhPub")),
                Crypto.privateKey(Crypto.unb64(p.getString("sig"))), Crypto.unb64(p.getString("sigPub")),
            )
        } catch (e: GeneralSecurityException) {
            throw E2eeException("bad_blob", "Не удалось расшифровать ключи")
        } catch (e: JSONException) {
            throw E2eeException("bad_blob", "Не удалось расшифровать ключи")
        }
    }
}

/** Локальная защита данных ключом из Android Keystore (ключ не покидает хранилище). */
object SecureStore {
    private const val ALIAS = "freedom_local_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun seal(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())          // IV создаёт Keystore
        val ct = c.doFinal(plain)
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    fun open(sealed: String): ByteArray {
        val (iv, ct) = sealed.split(":")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.DEFAULT)))
        return c.doFinal(Base64.decode(ct, Base64.DEFAULT))
    }
}

/** Личность на устройстве: приватные ключи лежат зашифрованными ключом Keystore. */
object IdentityStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("e2ee", Context.MODE_PRIVATE)

    fun save(ctx: Context, username: String, id: Identity) {
        val json = JSONObject()
            .put("dh", Crypto.b64(id.dhPriv.encoded)).put("dhPub", Crypto.b64(id.dhSpki))
            .put("sig", Crypto.b64(id.sigPriv.encoded)).put("sigPub", Crypto.b64(id.sigSpki))
        prefs(ctx).edit()
            .putString("user", username)
            .putString("identity", SecureStore.seal(json.toString().toByteArray()))
            .apply()
    }

    fun load(ctx: Context, username: String): Identity? {
        val p = prefs(ctx)
        if (p.getString("user", null) != username) return null
        val sealed = p.getString("identity", null) ?: return null
        return try {
            val o = JSONObject(String(SecureStore.open(sealed), Charsets.UTF_8))
            Identity(
                Crypto.privateKey(Crypto.unb64(o.getString("dh"))), Crypto.unb64(o.getString("dhPub")),
                Crypto.privateKey(Crypto.unb64(o.getString("sig"))), Crypto.unb64(o.getString("sigPub")),
            )
        } catch (e: Exception) {
            null   // ключ Keystore потерян (сброс, смена экрана блокировки): нужно войти заново
        }
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}

/** Закреплённые ключи собеседников (доверие при первом использовании). Отдельно для каждого сервера. */
class Pins(ctx: Context, serverUrl: String) {
    private val p = ctx.getSharedPreferences(
        "pins_" + Crypto.b64(Crypto.sha256(Crypto.u(serverUrl.trim().trimEnd('/')))).take(10).replace('/', '_').replace('+', '-'),
        Context.MODE_PRIVATE,
    )

    fun get(username: String): String? = p.getString(username, null)

    fun pin(username: String, dh: String, sig: String) {
        val v = "$dh|$sig"
        val cur = p.getString(username, null)
        if (cur == null) p.edit().putString(username, v).apply()
        else if (cur != v) throw E2eeException("key_changed", "Ключ безопасности пользователя $username изменился")
    }
}

class E2ee(
    private val api: ApiClient,
    private val token: String,
    private val me: String,
    private val id: Identity,
    private val pins: Pins,
) {
    private class Peer(val dhSpki: ByteArray, val sigSpki: ByteArray) {
        val dhPub: PublicKey = Crypto.publicKey(dhSpki)
        val sigPub: PublicKey = Crypto.publicKey(sigSpki)
    }

    private val lock = Mutex()                                   // согласование ключей идёт по одному
    private val keys = ConcurrentHashMap<String, ByteArray>()    // "комната:эпоха" -> ключ сообщений
    private val latest = ConcurrentHashMap<Long, Int>()          // комната -> актуальная эпоха на сервере
    private val peers = ConcurrentHashMap<String, Peer>()
    private val missTried = ConcurrentHashMap.newKeySet<String>()
    private val badEnv = ConcurrentHashMap.newKeySet<String>()

    init {
        pins.pin(me, Crypto.b64(id.dhSpki), Crypto.b64(id.sigSpki))   // свои ключи тоже закрепляем
    }

    private fun rk(room: Long, epoch: Int) = "$room:$epoch"
    private fun msgKey(k: ByteArray) = Crypto.hkdf(k, "FRDM-MSG-KEY-v1")

    // ---------- ключи собеседников ----------

    private fun loadPeer(username: String, dh: String, sig: String): Peer {
        pins.pin(username, dh, sig)                              // key_changed, если ключ подменили
        return Peer(Crypto.unb64(dh), Crypto.unb64(sig)).also { peers[username] = it }
    }

    private suspend fun peer(username: String): Peer {
        peers[username]?.let { return it }
        pins.get(username)?.let { pinned ->
            val (d, s) = pinned.split("|")
            return loadPeer(username, d, s)
        }
        val m = api.userKeys(token, username)
        return loadPeer(username, m.dhPub, m.sigPub)
    }

    private suspend fun memberKeys(roomId: Long): List<Pair<String, Peer>> =
        api.members(token, roomId).map { it.username to loadPeer(it.username, it.dhPub, it.sigPub) }

    // ---------- ключи комнат ----------

    private fun wrap(roomId: Long, epoch: Int, k: ByteArray, recipient: String, peer: Peer): JSONObject {
        val eph = Crypto.generateEc()
        val ephSpki = eph.public.encoded
        val info = Crypto.u("FRDM-WRAP-v1|$roomId|$epoch|$me|$recipient")
        val wk = Crypto.hkdf(Crypto.ecdh(eph.private, peer.dhPub), info)
        val n = Crypto.random(12)
        val c = Crypto.gcmEncrypt(wk, n, k, info)
        val sig = Crypto.sign(
            id.sigPriv,
            Crypto.cat(Crypto.u("FRDM-ENV-v1|$roomId|$epoch|$me|$recipient"), byteArrayOf(0), ephSpki, n, c),
        )
        return JSONObject().put("recipient", recipient).put("eph", Crypto.b64(ephSpki))
            .put("n", Crypto.b64(n)).put("c", Crypto.b64(c)).put("sig", Crypto.b64(sig))
    }

    private suspend fun unwrap(e: Envelope) {
        val s = peer(e.sender)
        withContext(Dispatchers.Default) {
            val eph = Crypto.unb64(e.eph)
            val n = Crypto.unb64(e.n)
            val c = Crypto.unb64(e.c)
            val ok = Crypto.verify(
                s.sigPub, Crypto.unb64(e.sig),
                Crypto.cat(Crypto.u("FRDM-ENV-v1|${e.roomId}|${e.epoch}|${e.sender}|$me"), byteArrayOf(0), eph, n, c),
            )
            if (!ok) throw E2eeException("bad_sig", "Подпись ключа комнаты неверна")
            val info = Crypto.u("FRDM-WRAP-v1|${e.roomId}|${e.epoch}|${e.sender}|$me")
            val wk = Crypto.hkdf(Crypto.ecdh(id.dhPriv, Crypto.publicKey(eph)), info)
            keys[rk(e.roomId, e.epoch)] = msgKey(Crypto.gcmDecrypt(wk, n, c, info))
        }
    }

    private suspend fun fetch(roomId: Long?) {
        val r = api.keys(token, roomId)
        for ((rid, ep) in r.latest) latest[rid] = ep
        for (e in r.envelopes) {
            val k = rk(e.roomId, e.epoch)
            if (keys.containsKey(k) || k in badEnv) continue
            try {
                unwrap(e)
            } catch (ex: E2eeException) {
                if (ex.code == "key_changed") throw ex
                badEnv.add(k)
            } catch (ex: GeneralSecurityException) {
                badEnv.add(k)
            } catch (ex: IllegalArgumentException) {
                badEnv.add(k)
            }
        }
    }

    /** Подтягивает конверты с ключами (одной комнаты или всех, если roomId == null). */
    suspend fun fetchKeys(roomId: Long?) = lock.withLock { fetch(roomId) }

    fun noteEpoch(roomId: Long, epoch: Int) {
        if (epoch > (latest[roomId] ?: 0)) latest[roomId] = epoch
    }

    /** Гарантирует ключ актуальной эпохи; если нас в ней нет (новый участник) — создаёт новую эпоху. */
    suspend fun ensureKey(roomId: Long): Int {
        lock.withLock {
            for (attempt in 0 until 4) {
                var ep = latest[roomId] ?: 0
                if (ep > 0 && keys.containsKey(rk(roomId, ep))) return ep
                fetch(roomId)
                ep = latest[roomId] ?: 0
                if (ep > 0 && keys.containsKey(rk(roomId, ep))) return ep
                val members = memberKeys(roomId)                  // заодно сверяет закреплённые ключи
                val next = ep + 1
                val k = Crypto.random(32)
                val envelopes = withContext(Dispatchers.Default) {
                    JSONArray().also { arr -> for ((name, p) in members) arr.put(wrap(roomId, next, k, name, p)) }
                }
                try {
                    api.postKeys(token, roomId, next, envelopes)
                } catch (ex: ApiException) {
                    if (ex.code == 409) continue                  // кто-то опередил — берём его эпоху
                    throw ex
                }
                keys[rk(roomId, next)] = msgKey(k)
                latest[roomId] = next
                return next
            }
        }
        throw E2eeException("no_key", "Не удалось согласовать ключ комнаты")
    }

    // ---------- сообщения ----------

    /** Шифрует и подписывает сообщение; ключ комнаты при необходимости создаётся. */
    suspend fun seal(roomId: Long, payload: JSONObject): SealedMsg {
        val epoch = ensureKey(roomId)
        return withContext(Dispatchers.Default) {
            val mk = keys[rk(roomId, epoch)] ?: throw E2eeException("no_key")
            val aad = Crypto.u("FRDM-MSG-v1|$roomId|$epoch|$me")
            val n = Crypto.random(12)
            val ct = Crypto.cat(n, Crypto.gcmEncrypt(mk, n, Crypto.u(payload.toString()), aad))
            val sig = Crypto.sign(id.sigPriv, Crypto.cat(aad, byteArrayOf(0), ct))
            SealedMsg(epoch, Crypto.b64(ct), Crypto.b64(sig))
        }
    }

    /** Проверяет подпись отправителя и расшифровывает. Бросает E2eeException(no_key | bad_sig | bad_data). */
    suspend fun decryptMessage(m: RawMessage): JSONObject {
        val k = rk(m.roomId, m.epoch)
        if (!keys.containsKey(k) && missTried.add(k)) {
            try { fetchKeys(m.roomId) } catch (e: ApiException) { /* нет сети: попробуем позже */ missTried.remove(k) }
        }
        val mk = keys[k] ?: throw E2eeException("no_key")
        val p = peer(m.username)
        return withContext(Dispatchers.Default) {
            try {
                val ct = Crypto.unb64(m.ct ?: throw E2eeException("bad_data"))
                val aad = Crypto.u("FRDM-MSG-v1|${m.roomId}|${m.epoch}|${m.username}")
                if (!Crypto.verify(p.sigPub, Crypto.unb64(m.sig ?: ""), Crypto.cat(aad, byteArrayOf(0), ct))) {
                    throw E2eeException("bad_sig")
                }
                val plain = Crypto.gcmDecrypt(mk, ct.copyOfRange(0, 12), ct.copyOfRange(12, ct.size), aad)
                JSONObject(String(plain, Charsets.UTF_8))
            } catch (e: GeneralSecurityException) {
                throw E2eeException("bad_data")
            } catch (e: JSONException) {
                throw E2eeException("bad_data")
            } catch (e: IllegalArgumentException) {
                throw E2eeException("bad_data")
            } catch (e: IndexOutOfBoundsException) {
                throw E2eeException("bad_data")
            }
        }
    }

    // ---------- файлы: блоки по 1 МиБ, у каждого файла свой ключ ----------

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    /** Шифрует src в dst блоками; ключ и nonce возвращает (их кладут внутрь зашифрованного сообщения). */
    fun encryptFile(src: File, dst: File): FileSecret {
        val key = Crypto.random(32)
        val base = Crypto.random(12)
        val size = src.length()
        val chunks = maxOf(1L, (size + Crypto.CHUNK - 1) / Crypto.CHUNK).toInt()
        try {
            src.inputStream().buffered().use { input ->
                dst.outputStream().buffered().use { out ->
                    val buf = ByteArray(Crypto.CHUNK)
                    for (i in 0 until chunks) {
                        val len = readFully(input, buf)
                        out.write(Crypto.gcmEncrypt(key, Crypto.chunkNonce(base, i), buf.copyOf(len), Crypto.chunkAad(i, i == chunks - 1)))
                    }
                }
            }
        } catch (e: Exception) {
            dst.delete()
            throw e
        }
        return FileSecret(Crypto.b64(key), Crypto.b64(base), size)
    }

    /** Расшифровывает src в dst. При любой подмене или усечении бросает E2eeException(bad_data). */
    fun decryptFile(src: File, dst: File, keyB64: String, nonceB64: String) {
        val key = Crypto.unb64(keyB64)
        val base = Crypto.unb64(nonceB64)
        val step = Crypto.CHUNK + 16
        val total = src.length()
        val chunks = maxOf(1L, (total + step - 1) / step).toInt()
        try {
            src.inputStream().buffered().use { input ->
                dst.outputStream().buffered().use { out ->
                    val buf = ByteArray(step)
                    for (i in 0 until chunks) {
                        val len = readFully(input, buf)
                        out.write(Crypto.gcmDecrypt(key, Crypto.chunkNonce(base, i), buf.copyOf(len), Crypto.chunkAad(i, i == chunks - 1)))
                    }
                }
            }
        } catch (e: GeneralSecurityException) {
            dst.delete()
            throw E2eeException("bad_data", "Файл повреждён или подменён")
        } catch (e: Exception) {
            dst.delete()
            throw e
        }
    }

    // ---------- звонки ----------

    private suspend fun callKey(peerName: String, callId: String): ByteArray {
        val p = peer(peerName)
        return withContext(Dispatchers.Default) {
            Crypto.hkdf(Crypto.ecdh(id.dhPriv, p.dhPub), "FRDM-CALL-v1|$callId")
        }
    }

    /** SDP и ICE-кандидаты шифруются ключом двух собеседников: сервер их не читает и не подменяет. */
    suspend fun sealSignal(peerName: String, callId: String, obj: JSONObject): JSONObject {
        val key = callKey(peerName, callId)
        val n = Crypto.random(12)
        val c = Crypto.gcmEncrypt(key, n, Crypto.u(obj.toString()), Crypto.u("FRDM-SIG-v1|$callId|$me|$peerName"))
        return JSONObject().put("n", Crypto.b64(n)).put("c", Crypto.b64(c))
    }

    suspend fun openSignal(peerName: String, callId: String, data: JSONObject): JSONObject {
        val key = callKey(peerName, callId)
        try {
            val plain = Crypto.gcmDecrypt(
                key, Crypto.unb64(data.getString("n")), Crypto.unb64(data.getString("c")),
                Crypto.u("FRDM-SIG-v1|$callId|$peerName|$me"),
            )
            return JSONObject(String(plain, Charsets.UTF_8))
        } catch (e: GeneralSecurityException) {
            throw E2eeException("bad_data", "Сигнал звонка не прошёл проверку")
        } catch (e: JSONException) {
            throw E2eeException("bad_data", "Сигнал звонка не прошёл проверку")
        }
    }

    // ---------- номер безопасности ----------

    /** 8 групп по 5 цифр; одинаков у обоих собеседников. Сверяется лично или по другому каналу. */
    suspend fun safetyNumber(other: String): String {
        val p = peer(other)
        val a = me to Crypto.sha256(Crypto.u("FRDM-FP-v1|$me|"), id.dhSpki, id.sigSpki)
        val b = other to Crypto.sha256(Crypto.u("FRDM-FP-v1|$other|"), p.dhSpki, p.sigSpki)
        val (x, y) = listOf(a, b).sortedWith { l, r -> l.first.compareTo(r.first) }
        val h = Crypto.sha256(x.second, y.second)
        return (0 until 8).joinToString(" ") { i ->
            val v = ((h[4 * i].toLong() and 0xff) shl 24) or ((h[4 * i + 1].toLong() and 0xff) shl 16) or
                ((h[4 * i + 2].toLong() and 0xff) shl 8) or (h[4 * i + 3].toLong() and 0xff)
            (v % 100000).toString().padStart(5, '0')
        }
    }
}
