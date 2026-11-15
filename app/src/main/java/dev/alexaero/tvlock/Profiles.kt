package dev.alexaero.tvlock

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * A person who can unlock the TV: their own name, picture and PIN/password. Any profile's PIN unlocks
 * the TV, and the lock greets whoever it was.
 */
data class Profile(
    val id: String,
    val name: String,
    val kind: SecretKind,
    val pinLength: Int,
    val hash: String,
    val salt: String,
    val alg: String,
    /** Colour avatar (index into [Profiles.palette]) used when there's no photo. */
    val color: Int,
    /** Bumped when the photo changes; 0 = no photo. */
    val photo: Long,
) {
    val initial get() = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val kindText get() = when (kind) {
        SecretKind.PIN -> "$pinLength-digit PIN"
        SecretKind.PIN_ANY -> "PIN"
        SecretKind.PASSWORD -> "Password"
    }

    fun json(withSecret: Boolean = false): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("kind", kind.name).put("pinLength", pinLength)
        .put("color", color).put("photo", photo)
        .apply { if (withSecret) put("hash", hash).put("salt", salt).put("alg", alg) }

    companion object {
        fun from(o: JSONObject) = Profile(
            o.getString("id"), o.getString("name"),
            runCatching { SecretKind.valueOf(o.getString("kind")) }.getOrDefault(SecretKind.PIN),
            o.optInt("pinLength", 4), o.getString("hash"), o.getString("salt"),
            o.optString("alg", "PBKDF2WithHmacSHA256"), o.optInt("color"), o.optLong("photo"),
        )
    }
}

object Profiles {
    private const val ITER = 12_000
    private val rng = SecureRandom()

    /** Avatar colours (gradient pairs) in the TV Lock palette. */
    val palette = listOf(
        0xFFF4EBD9 to 0xFFDCC7A6, // cream
        0xFFE2B98C to 0xFFC89B6D, // caramel
        0xFFD9E4C6 to 0xFFAFC191, // sage
        0xFFF2CFC4 to 0xFFDDA192, // rose
        0xFFD3E0EE to 0xFFA4BCD6, // sky
        0xFFE5D9F2 to 0xFFC0A9DD, // lilac
    )

    /** Changes whenever a profile is added, edited or removed (screens refresh on it). */
    val changed = MutableStateFlow(0)

    fun list(p: Prefs): List<Profile> {
        migrate(p)
        return runCatching {
            val a = JSONArray(p.profiles ?: "[]")
            (0 until a.length()).map { Profile.from(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun get(p: Prefs, id: String?) = list(p).firstOrNull { it.id == id }

    @Synchronized
    private fun save(p: Prefs, list: List<Profile>) {
        p.profiles = JSONArray().apply { list.forEach { put(it.json(true)) } }.toString()
        changed.value++
    }

    /** The single PIN from TV Lock 1.2 and older becomes the first profile. */
    @Synchronized
    private fun migrate(p: Prefs) {
        if (p.profiles != null) return
        val hash = p.hash
        val list = if (hash == null) JSONArray() else JSONArray().put(
            Profile(id(), "Me", p.secretKind, p.pinLength, hash, p.salt.orEmpty(), p.alg ?: "PBKDF2WithHmacSHA256", 0, 0).json(true)
        )
        p.profiles = list.toString()
    }

    private fun id() = Base64.encodeToString(ByteArray(6).also { rng.nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun pickAlg(): String =
        runCatching { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256"); "PBKDF2WithHmacSHA256" }
            .getOrDefault("PBKDF2WithHmacSHA1") // Android 7.x

    private fun derive(alg: String, s: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance(alg).generateSecret(PBEKeySpec(s.toCharArray(), salt, ITER, 256)).encoded

    private fun hashOf(s: String): Triple<String, String, String> {
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        val alg = pickAlg()
        return Triple(
            Base64.encodeToString(derive(alg, s, salt), Base64.NO_WRAP),
            Base64.encodeToString(salt, Base64.NO_WRAP), alg,
        )
    }

    fun checks(pr: Profile, s: String): Boolean = runCatching {
        val got = derive(pr.alg, s, Base64.decode(pr.salt, Base64.NO_WRAP))
        MessageDigest.isEqual(got, Base64.decode(pr.hash, Base64.NO_WRAP))
    }.getOrDefault(false)

    /** Whose PIN is this? (Slow on purpose: call off the main thread.) */
    fun match(p: Prefs, s: String): Profile? = list(p).firstOrNull { checks(it, s) }

    /** Checks a new PIN: returns an error message, or null if it's fine. */
    fun validate(p: Prefs, s: String, kind: SecretKind, exceptId: String? = null): String? {
        when (kind) {
            SecretKind.PIN, SecretKind.PIN_ANY -> {
                if (!s.all { it.isDigit() }) return "A PIN can only have numbers."
                if (s.length !in 4..16) return "Use 4 to 16 digits."
            }
            SecretKind.PASSWORD -> if (s.length !in 4..32) return "Use 4 to 32 characters."
        }
        val owner = list(p).firstOrNull { it.id != exceptId && checks(it, s) }
        if (owner != null) return "${owner.name} already uses that ${if (kind == SecretKind.PASSWORD) "password" else "PIN"}. Pick a different one."
        return null
    }

    fun add(p: Prefs, name: String, color: Int, s: String, kind: SecretKind): Profile {
        val (h, salt, alg) = hashOf(s)
        val pr = Profile(id(), name.trim().take(24).ifEmpty { "Me" }, kind, if (kind == SecretKind.PIN) s.length else 0, h, salt, alg,
            color.coerceIn(0, palette.lastIndex), 0)
        save(p, list(p) + pr)
        return pr
    }

    fun setSecret(p: Prefs, id: String, s: String, kind: SecretKind) {
        val (h, salt, alg) = hashOf(s)
        save(p, list(p).map { if (it.id == id) it.copy(kind = kind, pinLength = if (kind == SecretKind.PIN) s.length else 0, hash = h, salt = salt, alg = alg) else it })
    }

    fun update(p: Prefs, id: String, name: String? = null, color: Int? = null, photo: Long? = null) =
        save(p, list(p).map {
            if (it.id != id) it else it.copy(
                name = name?.trim()?.take(24)?.ifEmpty { null } ?: it.name,
                color = color?.coerceIn(0, palette.lastIndex) ?: it.color,
                photo = photo ?: it.photo,
            )
        })

    fun remove(ctx: Context, p: Prefs, id: String): Boolean {
        val l = list(p)
        if (l.size <= 1 || l.none { it.id == id }) return false
        photoFile(ctx, id).delete()
        save(p, l.filter { it.id != id })
        return true
    }

    fun photoFile(ctx: Context, id: String) = File(File(ctx.filesDir, "avatars").apply { mkdirs() }, "$id.jpg")

    /** What the lock screen asks for: a password keyboard if anyone uses one, else the number pad. */
    fun padFor(p: Prefs): Pair<SecretKind, Int> {
        val l = list(p)
        if (l.any { it.kind == SecretKind.PASSWORD }) return SecretKind.PASSWORD to 0
        val lens = l.map { if (it.kind == SecretKind.PIN) it.pinLength else -1 }.distinct()
        return if (lens.size == 1 && lens[0] > 0) SecretKind.PIN to lens[0] else SecretKind.PIN_ANY to 0
    }
}
