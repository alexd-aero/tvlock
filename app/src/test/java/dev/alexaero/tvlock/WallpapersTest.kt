package dev.alexaero.tvlock

import org.junit.Assert.assertEquals
import org.junit.Test

/** The same sample results page as the Aero theme's test (aegis-burrow test/themes.test.mjs). */
class WallpapersTest {
    private fun li(slug: String, id: String, res: String, alt: String,
                   th: String = "https://c4.wallpaperflare.com/wallpaper/1/2/3/$slug-hd-wallpaper-preview.jpg") =
        """<li itemprop="associatedMedia" itemscope>
    <a itemprop="url" href="https://www.wallpaperflare.com/$slug-wallpaper-$id" target="_blank"><img class="lazy" itemprop="contentUrl" alt="$alt HD wallpaper" data-src="$th"></a>
    <span class="res">$res</span><meta itemprop="contentSize" content="1.2MB"></li>"""

    private fun page(n: Int) = "<ul>" + (0 until n).joinToString("") {
        li("fog-forest-$it", "ab$it", if (it % 2 == 1) "3840x2160px" else "1080x1920px", "Fog &amp; forest $it, trees")
    } + li("bad", "x1", "1x1px", "Off-site preview", "https://evil.example/a.jpg") + li("fog-forest-0", "ab0", "3840x2160px", "Duplicate") + "</ul>"

    @Test fun parsesResults() {
        val items = Wallpapers.parse(page(3))
        assertEquals("off-site previews and duplicates are dropped", 3, items.length())
        val it1 = items.getJSONObject(1)
        assertEquals("Fog & forest 1, trees", it1.getString("title"))
        assertEquals("https://www.wallpaperflare.com/fog-forest-1-wallpaper-ab1", it1.getString("url"))
        assertEquals("https://c4.wallpaperflare.com/wallpaper/1/2/3/fog-forest-1-hd-wallpaper-preview.jpg", it1.getString("thumb"))
        assertEquals(3840, it1.getInt("w")); assertEquals(2160, it1.getInt("h"))
        assertEquals(80, Wallpapers.parse(page(80)).length())
    }

    @Test fun sniffsFiles() {
        assertEquals("gif", Look.sniff("GIF89a".toByteArray()))
        assertEquals("image", Look.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        assertEquals("video", Look.sniff(byteArrayOf(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D)))
        assertEquals(null, Look.sniff("<html>".toByteArray()))
    }
}
