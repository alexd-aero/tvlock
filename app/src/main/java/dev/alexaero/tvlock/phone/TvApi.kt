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

/** The TV this phone controls. */
class PhoneStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("phone", Context.MODE_PRIVATE)
    val host get() = sp.getString("host", null)
    val token get() = sp.getString("token", null)
    val name get() = sp.getString("name", null) ?: "your TV"
    val hasTv get() = host != null && token != null

    fun save(host: String, token: String, name: String) =
        sp.edit().putString("host", host).putString("token", token).putString("name", name).apply()

    fun clear() = sp.edit().clear().apply()

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
