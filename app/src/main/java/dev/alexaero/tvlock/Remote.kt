package dev.alexaero.tvlock

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom

/** A phone paired with this TV. Only a hash of its token is kept. */
data class Device(
    val id: String, val name: String, val hash: String, val added: Long, val lastSeen: Long,
    /** The person using this phone (their profile), so unlocking from it greets them. */
    val profileId: String? = null,
) {
    fun json(me: Boolean = false) = JSONObject()
        .put("id", id).put("name", name).put("added", added).put("lastSeen", lastSeen).put("me", me)
        .put("profile", profileId ?: JSONObject.NULL)
}

object Devices {
    fun list(p: Prefs): List<Device> = runCatching {
        val a = JSONArray(p.devices ?: "[]")
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Device(o.getString("id"), o.getString("name"), o.getString("hash"), o.optLong("added"), o.optLong("lastSeen"),
                o.optString("profile").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(p: Prefs, list: List<Device>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("hash", it.hash)
                .put("added", it.added).put("lastSeen", it.lastSeen).put("profile", it.profileId ?: ""))
        }
        p.devices = a.toString()
        Remote.devicesChanged.value++
    }

    fun hash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun find(p: Prefs, token: String): Device? {
        val h = hash(token)
        return list(p).firstOrNull { MessageDigest.isEqual(it.hash.toByteArray(), h.toByteArray()) }
    }

    fun remove(p: Prefs, id: String) = save(p, list(p).filter { it.id != id })

    fun link(p: Prefs, id: String, profileId: String?) = save(p, list(p).map { if (it.id == id) it.copy(profileId = profileId) else it })
}

/**
 * The phone remote: TV Lock serves a small web app on the home network. Phones pair by scanning a
 * QR code with a one-time code, then use a private token. Only private (home) addresses are answered;
 * nothing is opened on the router, so it does not work from outside the house.
 */
object Remote {
    const val PORT = 8642
    private const val TAG = "TvLockRemote"
    private val rng = SecureRandom()
    private var server: Server? = null

    @Volatile private var pairCode: String? = null
    @Volatile private var pairUntil = 0L
    @Volatile private var pairFails = 0
    /** Six digits to type into the phone app; valid together with [pairCode]. */
    @Volatile var shortCode: String? = null
        private set

    /** Setup was finished from a phone (the TV's setup screen moves on). */
    val setupFinished = MutableStateFlow(false)

    /** Name of the phone that was just paired (the TV's Phone screen shows it). */
    val justPaired = MutableStateFlow<String?>(null)
    val devicesChanged = MutableStateFlow(0)

    val running get() = server != null

    @Synchronized
    fun start(ctx: Context) {
        if (server != null) return
        val s = Server(ctx.applicationContext)
        try {
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            server = s
        } catch (e: Exception) {
            Log.w(TAG, "can't start the phone remote on :$PORT", e)
        }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    /** Start or stop to match the setting. */
    fun sync(ctx: Context) = if (Prefs(ctx).phoneAccess) start(ctx) else stop()

    fun token(bytes: Int): String =
        Base64.encodeToString(ByteArray(bytes).also { rng.nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** A new one-time pairing code, good for 10 minutes. */
    fun newPairCode(): String {
        val c = token(12)
        pairCode = c
        shortCode = (rng.nextInt(900_000) + 100_000).toString()
        pairUntil = SystemClock.elapsedRealtime() + 10 * 60_000L
        pairFails = 0
        return c
    }

    fun endPairing() { pairCode = null; shortCode = null }

    val pairingOpen get() = pairCode != null && SystemClock.elapsedRealtime() < pairUntil

    /** Swap the one-time code for a phone token. Returns (token, device) or null. */
    @Synchronized
    fun pair(p: Prefs, code: String, name: String): Pair<String, Device>? {
        val want = pairCode
        if (want == null || SystemClock.elapsedRealtime() > pairUntil || pairFails > 20) return null
        val digits = code.filter { it.isDigit() }
        val ok = MessageDigest.isEqual(want.toByteArray(), code.toByteArray()) ||
            (code.length <= 12 && digits.length == 6 && MessageDigest.isEqual(shortCode.orEmpty().toByteArray(), digits.toByteArray()))
        if (!ok) { pairFails++; return null }
        pairCode = null
        shortCode = null
        val tok = token(32)
        val now = System.currentTimeMillis()
        return tok to addDevice(p, tok, name)
    }

    /** Join an already set-up TV with any profile's PIN; the PIN also says who this phone belongs to. */
    @Synchronized
    fun pairWithPin(p: Prefs, pin: String, name: String): Triple<String, Device, Profile>? {
        val who = Profiles.match(p, pin) ?: return null
        val tok = token(32)
        return Triple(tok, addDevice(p, tok, name, who.id), who)
    }

    fun addDevice(p: Prefs, token: String, name: String, profileId: String? = null): Device {
        val now = System.currentTimeMillis()
        val d = Device(token(6), name.trim().take(40).ifEmpty { "Phone" }, Devices.hash(token), now, now, profileId)
        Devices.save(p, Devices.list(p) + d)
        justPaired.value = d.name
        return d
    }

    /** This TV's home-network addresses; Ethernet first. */
    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("eth")) 0 else if (it.name.startsWith("wlan")) 1 else 2 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    fun baseUrl(): String? = addresses().firstOrNull()?.let { "http://$it:$PORT" }

    /** Home addresses only: 10/8, 172.16/12, 192.168/16, link-local, CGNAT-free; IPv6 ULA/link-local. */
    fun isHome(ip: String?): Boolean {
        val a = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return false
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
        if (a is Inet6Address) return (a.address[0].toInt() and 0xfe) == 0xfc
        return false
    }

    fun tvName(): String = listOfNotNull(Build.MANUFACTURER.takeIf { it != "unknown" }, Build.MODEL)
        .joinToString(" ").replace('_', ' ')
}

private class Server(private val ctx: Context) : NanoHTTPD(Remote.PORT) {
    private val prefs = Prefs(ctx)

    override fun serve(s: IHTTPSession): Response {
        if (!Remote.isHome(s.remoteIpAddress)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "TV Lock only answers on the home network.")
        }
        val r = try {
            route(s)
        } catch (e: Exception) {
            json(JSONObject().put("error", e.message ?: e.javaClass.simpleName), Response.Status.INTERNAL_ERROR)
        }
        r.addHeader("Cache-Control", "no-store")
        r.addHeader("X-Content-Type-Options", "nosniff")
        return r
    }

    private fun route(s: IHTTPSession): Response {
        val path = s.uri
        val post = s.method == Method.POST
        when (path) {
            "/", "/index.html" -> return asset("web/index.html", "text/html; charset=utf-8")
            "/icon.png" -> return asset("web/icon.png", "image/png")
            "/manifest.webmanifest" -> return asset("web/manifest.webmanifest", "application/manifest+json")
            "/get" -> {
                val apk = java.io.File(ctx.applicationInfo.sourceDir)
                val html = String(ctx.assets.open("web/get.html").use { it.readBytes() })
                    .replace("{{TV}}", Remote.tvName())
                    .replace("{{SIZE}}", "about 1 MB")
                    .replace("{{VERSION}}", Updater.current(ctx))
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
            }
            "/download/TV-Lock.apk", "/download/phone" -> {
                // The newest phone app on GitHub Releases (it isn't inside the TV app).
                return newFixedLengthResponse(Response.Status.TEMPORARY_REDIRECT, MIME_PLAINTEXT, "")
                    .apply { addHeader("Location", Updater.latestPhoneApkUrl(ctx)) }
            }
            "/api/pair-pin" -> if (post) {
                val b = body(s)
                val now = System.currentTimeMillis()
                if (now < prefs.lockoutUntil) {
                    val secs = (prefs.lockoutUntil - now) / 1000 + 1
                    return json(JSONObject().put("error", "Too many wrong PINs. Try again in $secs seconds."), Response.Status.TOO_MANY_REQUESTS)
                }
                val res = Remote.pairWithPin(prefs, b.optString("pin"), b.optString("name"))
                if (res == null) {
                    Secret.recordFail(prefs)
                    val left = (5 - prefs.fails).coerceAtLeast(0)
                    return json(JSONObject().put("error", if (left > 0) "That PIN isn't right. $left tries left." else "Too many wrong PINs. Wait a moment."),
                        Response.Status.FORBIDDEN)
                }
                Secret.recordSuccess(prefs)
                return json(JSONObject().put("token", res.first).put("device", res.second.json(true)).put("profile", res.third.json()))
            }
            "/api/hello" -> return json(
                JSONObject().put("app", "tvlock").put("name", Remote.tvName()).put("version", Updater.current(ctx))
                    .put("setupDone", prefs.setupDone).put("pairing", Remote.pairingOpen)
                    .put("service", LockService.instance != null)
            )
            "/api/pair" -> if (post) {
                val b = body(s)
                val res = Remote.pair(prefs, b.optString("code"), b.optString("name"))
                    ?: return json(JSONObject().put("error", "This code has expired. Open TV Lock → Phone on the TV and scan the new code."), Response.Status.FORBIDDEN)
                return json(JSONObject().put("token", res.first).put("device", res.second.json(true)))
            }
        }
        if (!path.startsWith("/api/")) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")

        val tok = (s.headers["authorization"] ?: "").removePrefix("Bearer ").trim()
        val me = if (tok.isNotEmpty()) Devices.find(prefs, tok) else null
        if (me == null) return json(JSONObject().put("error", "not paired"), Response.Status.UNAUTHORIZED)
        if (System.currentTimeMillis() - me.lastSeen > 60_000) {
            Devices.save(prefs, Devices.list(prefs).map { if (it.id == me.id) it.copy(lastSeen = System.currentTimeMillis()) else it })
        }

        return when (path) {
            "/api/state" -> json(Control.state(ctx, me))
            "/api/events" -> newChunkedResponse(Response.Status.OK, "text/event-stream", Live.stream(ctx, me.id))
                .apply { setGzipEncoding(false) }
            "/api/apps" -> json(JSONObject().put("apps", Control.apps(ctx)))
            "/api/icon" -> Control.icon(ctx, s.parms["pkg"].orEmpty())
                ?.let { newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(it), it.size.toLong()) }
                ?: newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
            "/api/profiles/photo" -> {
                val id = s.parms["id"].orEmpty()
                if (post) {
                    val len = s.headers["content-length"]?.toIntOrNull() ?: 0
                    if (len <= 0 || len > 8 * 1024 * 1024) return json(JSONObject().put("error", "Pick a smaller photo."), Response.Status.BAD_REQUEST)
                    val buf = ByteArray(len)
                    var r = 0
                    while (r < len) { val n = s.inputStream.read(buf, r, len - r); if (n < 0) break; r += n }
                    val ok = Control.setPhoto(ctx, id, buf.copyOf(r))
                    return if (ok) json(JSONObject().put("ok", true))
                    else json(JSONObject().put("error", "That photo couldn't be read."), Response.Status.BAD_REQUEST)
                }
                val f = Profiles.photoFile(ctx, id)
                if (!f.exists()) newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
                else newFixedLengthResponse(Response.Status.OK, "image/jpeg", f.inputStream(), f.length())
            }
            else -> {
                if (!post) return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "")
                val b = body(s)
                val ok = when (path) {
                    "/api/unlock" -> Control.unlock(ctx, me)
                    "/api/me/link" -> {
                        val pid = b.optString("profile")
                        if (Profiles.get(prefs, pid) == null) return json(JSONObject().put("error", "No such profile."), Response.Status.BAD_REQUEST)
                        Devices.link(prefs, me.id, pid); true
                    }
                    "/api/me/link-pin" -> {
                        val who = Profiles.match(prefs, b.optString("pin"))
                            ?: return json(JSONObject().put("error", "That PIN isn't right."), Response.Status.FORBIDDEN)
                        Devices.link(prefs, me.id, who.id)
                        return json(JSONObject().put("ok", true).put("profile", who.json()))
                    }
                    "/api/lock" -> Control.lockNow(ctx)
                    "/api/update/check" -> Control.checkUpdate(ctx)
                    "/api/update/install" -> Control.installUpdate(ctx)
                    "/api/key" -> Control.key(ctx, b.optString("key"))
                    "/api/text" -> Control.type(b.optString("text"), b.optBoolean("submit"))
                    "/api/launch" -> Control.launch(ctx, b.optString("pkg"))
                    "/api/settings" -> Control.settings(ctx, b)
                    "/api/profiles/add" -> {
                        val (err, pr) = Control.addProfile(ctx, b)
                        return if (err != null) json(JSONObject().put("error", err), Response.Status.BAD_REQUEST)
                        else json(JSONObject().put("ok", true).put("profile", pr!!.json()))
                    }
                    "/api/profiles/pin" -> return Control.profilePin(ctx, b)?.let { json(JSONObject().put("error", it), Response.Status.BAD_REQUEST) }
                        ?: json(JSONObject().put("ok", true))
                    "/api/profiles/update" -> {
                        Profiles.update(prefs, b.optString("id"),
                            name = b.optString("name").takeIf { b.has("name") },
                            color = if (b.has("color")) b.getInt("color") else null)
                        true
                    }
                    "/api/profiles/delete" -> {
                        if (!Profiles.remove(ctx, prefs, b.optString("id")))
                            return json(JSONObject().put("error", "Keep at least one profile."), Response.Status.BAD_REQUEST)
                        true
                    }
                    "/api/profiles/photo/remove" -> Control.removePhoto(ctx, b.optString("id"))
                    "/api/setup/finish" -> {
                        if (!prefs.hasSecret) return json(JSONObject().put("error", "Set a PIN first."), Response.Status.BAD_REQUEST)
                        prefs.enabled = true
                        prefs.markBootUnlocked()
                        prefs.setupDone = true
                        Remote.setupFinished.value = true
                        true
                    }
                    "/api/devices/remove" -> { Devices.remove(prefs, b.optString("id")); true }
                    "/api/devices/rename" -> {
                        val n = b.optString("name").trim().take(40)
                        if (n.isNotEmpty()) Devices.save(prefs, Devices.list(prefs).map { if (it.id == b.optString("id")) it.copy(name = n) else it })
                        n.isNotEmpty()
                    }
                    else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
                }
                json(JSONObject().put("ok", ok))
            }
        }
    }

    private fun body(s: IHTTPSession): JSONObject {
        val len = s.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return JSONObject()
        require(len <= 64 * 1024) { "too big" }
        val buf = ByteArray(len)
        var r = 0
        while (r < len) {
            val n = s.inputStream.read(buf, r, len - r)
            if (n < 0) break
            r += n
        }
        return runCatching { JSONObject(String(buf, 0, r)) }.getOrDefault(JSONObject())
    }

    private fun asset(name: String, mime: String): Response {
        val bytes = ctx.assets.open(name).use { it.readBytes() }
        return newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun json(o: JSONObject, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json", o.toString())
}
