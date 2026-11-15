package dev.alexaero.tvlock.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Sends this app's traffic over Wi-Fi. The TV is on the home network, but phones may route it elsewhere:
 * Samsung's "Switch to mobile data" when Wi-Fi seems weak, or a VPN that captures private addresses
 * outside the phone's own subnet (another Wi-Fi in the house). Binding to Wi-Fi avoids both where Android allows it.
 */
object PhoneNet {
    fun preferWifi(ctx: Context) = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val wifi = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        }
        cm.bindProcessToNetwork(wifi) // null (no Wi-Fi) puts it back to the default
    }

    /** "Wi-Fi 192.168.2.40" etc., for the can't-reach screen. */
    fun describe(ctx: Context): String = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val n = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(n)
        val kind = when {
            caps == null -> "No network"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            else -> "Network"
        }
        val ip = cm.getLinkProperties(n)?.linkAddresses?.firstOrNull { it.address is java.net.Inet4Address }?.address?.hostAddress
        if (ip != null) "$kind · $ip" else kind
    }.getOrDefault("Unknown")
}
