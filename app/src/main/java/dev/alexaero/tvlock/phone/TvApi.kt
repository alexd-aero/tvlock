package dev.alexaero.tvlock.phone

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiError(message: String) : Exception(message)

/** Talks to TV Lock's server on the TV (host = "ip:8642"). Blocking: call from Dispatchers.IO. */
class TvApi(val host: String, var token: String? = null) {
    private fun open(path: String, timeout: Int): HttpURLConnection =
        (URL("http://$host$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = timeout
            readTimeout = timeout * 3
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    fun get(path: String, timeout: Int = 4000): JSONObject = read(open(path, timeout))

    fun post(path: String, body: JSONObject = JSONObject(), timeout: Int = 6000): JSONObject {
        val c = open(path, timeout)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return read(c)
    }

    fun upload(path: String, jpeg: ByteArray): JSONObject {
        val c = open(path, 8000)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "image/jpeg")
        c.setFixedLengthStreamingMode(jpeg.size)
        c.outputStream.use { it.write(jpeg) }
        return read(c)
    }

    fun bytes(path: String): ByteArray? = runCatching {
        val c = open(path, 4000)
        if (c.responseCode in 200..299) c.inputStream.use { it.readBytes() } else null
    }.getOrNull()

    private fun read(c: HttpURLConnection): JSONObject {
        try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            val j = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (code !in 200..299) throw ApiError(j.optString("error").ifEmpty { "The TV answered with an error ($code)" })
            return j
        } finally {
            c.disconnect()
        }
    }

    fun hello(): JSONObject? = runCatching { get("/api/hello", 1500).takeIf { it.optString("app") == "tvlock" } }.getOrNull()

    /** One-time code (from the QR / download page, or the 6 digits on the TV) → this phone's token. */
    fun pair(code: String, name: String): String {
        val j = post("/api/pair", JSONObject().put("code", code).put("name", name))
        return j.getString("token").also { token = it }
    }
}

/** A TV this phone has joined. */
data class SavedTv(val host: String, val token: String, val name: String)

/** The TVs this phone controls, and which one is open. */
class PhoneStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("phone", Context.MODE_PRIVATE)

    init {
        // 1.3 kept a single TV in host/token/name.
        val h = sp.getString("host", null)
        val t = sp.getString("token", null)
        if (h != null && t != null && sp.getString("tvs", null) == null) {
            write(listOf(SavedTv(h, t, sp.getString("name", null) ?: "TV")), h)
        }
    }

    val tvs: List<SavedTv>
        get() = runCatching {
            val a = org.json.JSONArray(sp.getString("tvs", "[]"))
            (0 until a.length()).map { a.getJSONObject(it).let { o -> SavedTv(o.getString("host"), o.getString("token"), o.getString("name")) } }
        }.getOrDefault(emptyList())

    private val current get() = tvs.firstOrNull { it.host == sp.getString("current", null) } ?: tvs.firstOrNull()
    val host get() = current?.host
    val token get() = current?.token
    val name get() = current?.name ?: "your TV"
    val hasTv get() = current != null

    private fun write(list: List<SavedTv>, current: String?) {
        val a = org.json.JSONArray()
        list.forEach { a.put(JSONObject().put("host", it.host).put("token", it.token).put("name", it.name)) }
        sp.edit().putString("tvs", a.toString()).putString("current", current).remove("host").remove("token").remove("name").apply()
    }

    /** Adds or updates a TV and makes it the open one. */
    fun save(host: String, token: String, name: String) =
        write(tvs.filter { it.host != host } + SavedTv(host, token, name), host)

    fun select(host: String) = write(tvs, host)

    fun remove(host: String) {
        val rest = tvs.filter { it.host != host }
        write(rest, rest.firstOrNull()?.host)
    }

    /** Forget the open TV. */
    fun clear() { host?.let { remove(it) } }

    companion object {
        /** What the TV will call this phone. */
        fun phoneName(ctx: Context): String =
            (if (Build.VERSION.SDK_INT >= 25) Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) else null)
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }
}

/** tvlock://setup links that arrive while the app is open. */
object PhoneLinks {
    val incoming = MutableStateFlow<Uri?>(null)
}
