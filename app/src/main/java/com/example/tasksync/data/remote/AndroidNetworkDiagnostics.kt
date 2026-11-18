package com.example.tasksync.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/** Produces a small transport summary for debug troubleshooting without request data. */
internal object AndroidNetworkDiagnostics {
    fun summary(context: Context): String = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return "no-active-network"
        val capabilities = manager.getNetworkCapabilities(network) ?: return "capabilities-unavailable"
        val transports = buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (Build.VERSION.SDK_INT >= 26 && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) add("wifi-aware")
        }.ifEmpty { listOf("other") }
        "${transports.joinToString(",")}; internet=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}; validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
    }.getOrElse { "network-state-unavailable" }
}
