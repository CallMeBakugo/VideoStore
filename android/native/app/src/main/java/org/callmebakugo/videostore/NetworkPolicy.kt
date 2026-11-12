package org.callmebakugo.videostore

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

fun wifiAllowed(context: Context): Boolean {
    if (!context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("wifiOnly", false)) return true
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
