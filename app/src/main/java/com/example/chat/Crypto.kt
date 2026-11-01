package com.example.chat

import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Примитивы протокола FRDM-E2EE v1 (см. E2EE.md) поверх стандартного JCA.
 * Форматы совпадают с веб-клиентом (WebCrypto): публичные ключи — X.509 SPKI, приватные — PKCS#8,
 * подпись ECDSA — «сырая» r||s (64 байта; JCA отдаёт DER, поэтому есть преобразование).
 */
object Crypto {
    private val rng = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)
    fun u(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    fun cat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
        return out
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("SHA-256")
        for (p in parts) d.update(p)
        return d.digest()
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(data)
    }

    /** HKDF-SHA256 (RFC 5869) с пустой солью; выход 32 байта. */
    fun hkdf(ikm: ByteArray, info: ByteArray): ByteArray =
        hmac(hmac(ByteArray(32), ikm), cat(info, byteArrayOf(1)))

    fun hkdf(ikm: ByteArray, info: String): ByteArray = hkdf(ikm, u(info))

    /** PBKDF2-HMAC-SHA256, 32 байта (нужен API 26+). Тяжёлая операция: вызывать не в главном потоке. */
    fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    // ---------- AES-256-GCM (результат: шифртекст || тег 16 байт) ----------

    fun gcmEncrypt(key: ByteArray, nonce: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return c.doFinal(data)
    }

    /** Бросает GeneralSecurityException (AEADBadTagException), если данные изменены или ключ неверный. */
    fun gcmDecrypt(key: ByteArray, nonce: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return c.doFinal(data)
    }

    // ---------- эллиптические кривые P-256 ----------

    fun generateEc(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        return g.generateKeyPair()
    }

    fun publicKey(spki: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))

    fun privateKey(pkcs8: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    fun ecdh(priv: PrivateKey, pub: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }

    /** Подпись ECDSA-SHA256 в формате r||s (64 байта), как у WebCrypto. */
    fun sign(priv: PrivateKey, data: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(priv)
        s.update(data)
        return derToRaw(s.sign())
    }

    fun verify(pub: PublicKey, raw: ByteArray, data: ByteArray): Boolean {
        if (raw.size != 64) return false
        return try {
            val s = Signature.getInstance("SHA256withECDSA")
            s.initVerify(pub)
            s.update(data)
            s.verify(rawToDer(raw))
        } catch (e: GeneralSecurityException) {
            false
        }
    }

    /** DER SEQUENCE{INTEGER r, INTEGER s} -> 32 байта r || 32 байта s. */
    fun derToRaw(der: ByteArray): ByteArray {
        var off = 2
        if ((der[1].toInt() and 0x80) != 0) off = 2 + (der[1].toInt() and 0x7f)
        val out = ByteArray(64)
        for (part in 0..1) {
            val len = der[off + 1].toInt() and 0xff
            off += 2
            var start = off
            var l = len
            while (l > 32 && der[start].toInt() == 0) { start++; l-- }   // убираем ведущий нуль знака
            System.arraycopy(der, start, out, part * 32 + (32 - l), l)
            off += len
        }
        return out
    }

    /** 32 байта r || 32 байта s -> DER (JCA принимает подпись только в DER). */
    fun rawToDer(raw: ByteArray): ByteArray {
        val r = derInt(raw.copyOfRange(0, 32))
        val s = derInt(raw.copyOfRange(32, 64))
        val total = 4 + r.size + s.size
        val out = java.io.ByteArrayOutputStream()
        out.write(0x30)
        if (total > 127) out.write(0x81)
        out.write(total)
        out.write(0x02); out.write(r.size); out.write(r)
        out.write(0x02); out.write(s.size); out.write(s)
        return out.toByteArray()
    }

    /** Минимальное DER-представление неотрицательного целого (с нулевым байтом, если старший бит установлен). */
    private fun derInt(v: ByteArray): ByteArray = java.math.BigInteger(1, v).toByteArray()

    // ---------- блоки файла ----------

    const val CHUNK = 1 shl 20   // 1 МиБ открытого текста на блок

    /** nonce блока i: базовый nonce, у которого последние 4 байта XOR номер блока (big-endian). */
    fun chunkNonce(base: ByteArray, i: Int): ByteArray {
        val n = base.copyOf()
        n[8] = (n[8].toInt() xor (i ushr 24)).toByte()
        n[9] = (n[9].toInt() xor (i ushr 16)).toByte()
        n[10] = (n[10].toInt() xor (i ushr 8)).toByte()
        n[11] = (n[11].toInt() xor i).toByte()
        return n
    }

    /** AAD блока: метка, номер и признак последнего блока — защита от перестановки и усечения. */
    fun chunkAad(i: Int, last: Boolean): ByteArray = cat(
        u("FRDM-CHUNK-v1"),
        byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()),
        byteArrayOf(if (last) 1 else 0),
    )
}
