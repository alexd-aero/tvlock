package dev.alexaero.tvlock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class WallpaperError(message: String) : Exception(message)

/**
 * Wallpaper Flare, searched from the TV (like the Aero theme does from the gate). A browser check
 * ("Just a moment") is never worked around: the search says so and stops, and phones offer the
 * site in a real browser instead.
 */
object Wallpapers {
    private const val UA = "Mozilla/5.0 (Linux; Android 14; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0 Safari/537.36"
    private val THUMB = Regex("""^https://c\d\.wallpaperflare\.com/(?:wallpaper|preview)/[^?#\s"'<>]+\.(?:jpe?g|png|webp)$""", RegexOption.IGNORE_CASE)
    private val PAGE = Regex("""^https://www\.wallpaperflare\.com/[a-z0-9-]+-wallpaper-[a-z0-9]+$""", RegexOption.IGNORE_CASE)
    private val WPF = Regex("""^https?://(?:www\.)?wallpaperflare\.com/(?!search|static)([a-z0-9-]+-wallpaper-[a-z0-9]+)(?:/download(?:/[0-9x]+)?)?/?(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
    private val searched = ConcurrentHashMap<String, Pair<Long, JSONObject>>()
    private val thumbs = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ByteArray>) = size > 120
    }

    private class Got(val status: Int, val body: ByteArray, val url: String, val type: String?)

    private fun get(url: String, max: Int, referer: String? = null): Got {
        var u = URL(url)
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 12_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "image/*,video/*,text/html;q=0.8,*/*;q=0.5")
            referer?.let { c.setRequestProperty("Referer", it) }
            val code = c.responseCode
            if (code in 300..399 && c.getHeaderField("Location") != null) {
                u = URL(u, c.getHeaderField("Location")); c.disconnect(); return@repeat
            }
            val stream = (if (code < 400) c.inputStream else c.errorStream)
            val out = ByteArrayOutputStream()
            stream?.use { s ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = s.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) throw WallpaperError("That file is too big for a background.")
                }
            }
            val r = Got(code, out.toByteArray(), u.toString(), c.contentType)
            c.disconnect()
            return r
        }
        throw WallpaperError("Too many redirects.")
    }

    private fun challenged(r: Got) = r.status in listOf(403, 429, 503) ||
        Regex("""<title>\s*Just a moment|cf-chl-|challenge-platform/h/""", RegexOption.IGNORE_CASE)
            .containsMatchIn(String(r.body, 0, minOf(r.body.size, 20_000)))

    private fun unhtml(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
        .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")

    fun parse(html: String): JSONArray {
        val out = JSONArray()
        val seen = HashSet<String>()
        for (li in Regex("""<li itemprop="associatedMedia"[\s\S]*?</li>""").findAll(html).map { it.value }) {
            val href = Regex("""href="([^"]+)"""").findAll(li).map { unhtml(it.groupValues[1]) }.firstOrNull { PAGE.matches(it) } ?: continue
            val img = Regex("""<img\b[^>]*>""").find(li)?.value.orEmpty()
            val thumb = unhtml(Regex("""data-src="([^"]+)"""").find(img)?.groupValues?.get(1)
                ?: Regex("""\ssrc="([^"]+)"""").find(img)?.groupValues?.get(1).orEmpty())
            if (!THUMB.matches(thumb) || !seen.add(href)) continue
            val res = Regex("""class="res"[^>]*>\s*(\d+)\s*x\s*(\d+)""").find(li)
            val title = unhtml(Regex("""alt="([^"]*)"""").find(img)?.groupValues?.get(1).orEmpty())
                .replace(Regex("""\s*(HD|4K|8K)?\s*wallpaper\s*$""", RegexOption.IGNORE_CASE), "").trim()
            out.put(JSONObject().put("title", title.take(160).ifEmpty { "Wallpaper" }).put("url", href).put("thumb", thumb)
                .put("w", res?.groupValues?.get(1)?.toInt() ?: 0).put("h", res?.groupValues?.get(2)?.toInt() ?: 0))
        }
        return out
    }

    fun search(q0: String, page0: Int): JSONObject {
        val q = q0.replace(Regex("""[\u0000-\u001f]"""), " ").replace(Regex("""\s+"""), " ").trim().take(80)
        if (q.isEmpty()) throw WallpaperError("Type what you'd like: aurora, rainy city, misty forest…")
        val page = page0.coerceIn(1, 50)
        val key = "${q.lowercase()}|$page"
        searched[key]?.let { (at, v) -> if (System.currentTimeMillis() - at < 30 * 60_000) return v }
        val url = "https://www.wallpaperflare.com/search?wallpaper=" + java.net.URLEncoder.encode(q, "UTF-8") + if (page > 1) "&page=$page" else ""
        val r = get(url, 3_000_000)
        if (challenged(r)) throw WallpaperError("Wallpaper Flare is asking for a browser check right now, so the TV can't search it.")
        val items = if (r.status == 404 && page > 1) JSONArray() else {
            if (r.status >= 400) throw WallpaperError("Wallpaper Flare answered HTTP ${r.status}.")
            parse(String(r.body))
        }
        val v = JSONObject().put("q", q).put("page", page).put("items", items).put("more", items.length() >= 60).put("source", url)
        searched[key] = System.currentTimeMillis() to v
        return v
    }

    fun thumb(u: String): ByteArray {
        if (!THUMB.matches(u)) throw WallpaperError("Not a Wallpaper Flare preview.")
        synchronized(thumbs) { thumbs[u]?.let { return it } }
        val r = get(u, 2_000_000, "https://www.wallpaperflare.com/")
        if (r.status >= 400 || Look.sniff(r.body) != "image") throw WallpaperError("The preview didn't come (HTTP ${r.status}).")
        synchronized(thumbs) { thumbs[u] = r.body }
        return r.body
    }

    /** A link (a Wallpaper Flare page, or a picture/GIF/video) becomes the TV's background. */
    fun useLink(ctx: Context, link: String): String {
        val url = link.trim()
        if (!Regex("""^https?://\S+$""", RegexOption.IGNORE_CASE).matches(url)) throw WallpaperError("Paste an http(s) link.")
        val w = WPF.find(url)
        var r = get(if (w != null) "https://www.wallpaperflare.com/${w.groupValues[1]}/download" else url, 120_000_000,
            if (w != null) "https://www.wallpaperflare.com/${w.groupValues[1]}" else null)
        if (challenged(r) && (w != null || r.status >= 400)) throw WallpaperError(
            if (w != null) "Wallpaper Flare asked for a browser check, so the TV can't download it. Save it on your phone and upload it instead."
            else "The site refused (HTTP ${r.status}). Save it on your phone and upload it instead.")
        if (r.status >= 400) throw WallpaperError("The link answered HTTP ${r.status}.")
        if (Look.sniff(r.body) == null) {
            val html = String(r.body)
            val src = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).map { it.value }
                .firstOrNull { Regex("""\bid\s*=\s*["']show_img["']""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                ?.let { Regex("""\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                ?: Regex("""<meta[^>]+property=["']og:(?:video|image)(?::secure_url)?["'][^>]*>""", RegexOption.IGNORE_CASE).find(html)?.value
                    ?.let { Regex("""content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                ?: throw WallpaperError("That page doesn't show a picture the TV can find. Use the link to the picture itself.")
            r = get(URL(URL(r.url), unhtml(src)).toString(), 120_000_000, r.url)
            if (r.status >= 400) throw WallpaperError("The picture answered HTTP ${r.status}.")
        }
        val type = Look.sniff(r.body) ?: throw WallpaperError("That isn't a picture, GIF or video the TV can show.")
        val tmp = File(Look.bgFile(ctx).path + ".part")
        tmp.writeBytes(r.body)
        if (!tmp.renameTo(Look.bgFile(ctx))) throw WallpaperError("Couldn't save it.")
        Look.setBackground(ctx, type)
        return type
    }
}
