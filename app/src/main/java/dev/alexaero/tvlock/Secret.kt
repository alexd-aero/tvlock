package dev.alexaero.tvlock

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PIN/password storage: only a salted PBKDF2 hash is kept. */
object Secret {
    private const val ITER = 12_000

    private fun derive(alg: String, s: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance(alg).generateSecret(PBEKeySpec(s.toCharArray(), salt, ITER, 256)).encoded

    private fun pickAlg(): String =
        runCatching { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256"); "PBKDF2WithHmacSHA256" }
            .getOrDefault("PBKDF2WithHmacSHA1") // Android 7.x

    fun set(p: Prefs, s: String, kind: SecretKind, length: Int) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val alg = pickAlg()
        p.alg = alg
        p.salt = Base64.encodeToString(salt, Base64.NO_WRAP)
        p.hash = Base64.encodeToString(derive(alg, s, salt), Base64.NO_WRAP)
        p.secretKind = kind
        p.pinLength = length
        p.fails = 0
        p.lockoutUntil = 0
    }

    fun check(p: Prefs, s: String): Boolean {
        val hash = p.hash ?: return false
        val salt = Base64.decode(p.salt ?: return false, Base64.NO_WRAP)
        val got = derive(p.alg ?: "PBKDF2WithHmacSHA256", s, salt)
        return MessageDigest.isEqual(got, Base64.decode(hash, Base64.NO_WRAP))
    }

    /** Wrong answers: 5 free tries, then 30 s, 1 min, 2 min … up to 15 min. */
    fun recordFail(p: Prefs) {
        val n = p.fails + 1
        p.fails = n
        if (n >= 5) {
            val secs = (30L shl (n - 5).coerceAtMost(5)).coerceAtMost(900L)
            p.lockoutUntil = System.currentTimeMillis() + secs * 1000
        }
    }

    fun recordSuccess(p: Prefs) {
        p.fails = 0
        p.lockoutUntil = 0
    }
}
