package dev.alexaero.tvlock.phone

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** A TV found on the network: with TV Lock running (port 8642) and/or network debugging on (port 5555). */
data class FoundTv(
    val ip: String,
    val name: String?,
    val tvLock: Boolean,
    val adb: Boolean,
    val setupDone: Boolean? = null,
    val version: String? = null,
    /** TV Lock's lock service is running on the TV. */
    val service: Boolean? = null,
) {
    val host get() = "$ip:8642"
    val title get() = name ?: "Android TV"
}

/**
 * Looks for TVs on the home network. First this phone's own network and the usual router ranges,
 * then, if asked, every private network (192.168.x.x, 10.0.x.x, 172.16.x.x). Phones on another
 * Wi-Fi in the same house can usually still reach the TV, so all of these are tried.
 */
class Scanner(private val ctx: Context) {
    val found = MutableStateFlow<List<FoundTv>>(emptyList())
    val progress = MutableStateFlow(0f)
    val status = MutableStateFlow("")
    val running = MutableStateFlow(false)
    private val scanned = HashSet<String>()

    fun ownSubnets(): List<String> = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.flatMap { n ->
            cm.getLinkProperties(n)?.linkAddresses.orEmpty().mapNotNull { la ->
                (la.address as? Inet4Address)?.takeIf { it.isSiteLocalAddress }?.hostAddress?.substringBeforeLast('.')
            }
        }.distinct()
    }.getOrDefault(emptyList())

    private val common = listOf(
        "192.168.0", "192.168.1", "192.168.2", "192.168.3", "192.168.4", "192.168.5", "192.168.8", "192.168.10",
        "192.168.11", "192.168.31", "192.168.43", "192.168.50", "192.168.68", "192.168.86", "192.168.88",
        "192.168.100", "192.168.178", "10.0.0", "10.0.1", "10.1.1", "10.10.10", "172.16.0",
    )

    fun quickSubnets() = (ownSubnets() + common).distinct()

    fun everySubnet() = (0..255).map { "192.168.$it" } + (0..255).map { "10.0.$it" } + (0..255).map { "172.16.$it" }

    suspend fun scan(subnets: List<String>) = withContext(Dispatchers.IO) {
        val todo = subnets.filter { scanned.add(it) }
        if (todo.isEmpty()) return@withContext
        running.value = true
        progress.value = 0f
        val total = todo.size * 254
        val done = AtomicInteger()
        val hosts = Channel<String>(1024)
        try {
            coroutineScope {
                launch {
                    for (net in todo) {
                        status.value = "$net.x"
                        for (h in 1..254) hosts.send("$net.$h")
                    }
                    hosts.close()
                }
                repeat(256) {
                    launch {
                        for (ip in hosts) {
                            if (!isActive) break
                            probe(ip)?.let { tv -> found.value = (found.value.filter { it.ip != tv.ip } + tv).sortedBy { !it.tvLock } }
                            progress.value = done.incrementAndGet().toFloat() / total
                        }
                    }
                }
            }
        } finally {
            running.value = false
            status.value = ""
        }
    }

    /** Checks one address for TV Lock and for network debugging. */
    fun probe(ip: String): FoundTv? {
        val lock = open(ip, 8642)
        val adb = open(ip, 5555)
        if (!lock && !adb) return null
        val hello = if (lock) TvApi("$ip:8642").hello() else null
        if (!adb && hello == null) return null
        return FoundTv(
            ip, hello?.optString("name"), hello != null, adb,
            hello?.optBoolean("setupDone"), hello?.optString("version"), hello?.optBoolean("service"),
        )
    }

    private fun open(ip: String, port: Int, timeout: Int = 350): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeout) }
        true
    }.getOrDefault(false)

}
