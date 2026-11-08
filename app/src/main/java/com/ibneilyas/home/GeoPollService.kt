package com.ibneilyas.home

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.ibneilyas.home.data.HomeGeo

class GeoPollService : Service() {
    private var wasInside = true
    private val handler = Handler(Looper.getMainLooper())
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private fun watchNet() {
        if (netCb != null) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handler.post { HomeGeo.retryPending(this@GeoPollService, "network") }
                }
            }
            cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb)
            netCb = cb
        } catch (e: Exception) {
            HomeGeo.glog(this, "poll: net watch err " + (e.message ?: "?"))
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            checkLocation()
            handler.postDelayed(this, 120000L)
        }
    }
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        wasInside = true
        watchNet()
        handler.removeCallbacks(poll)
        handler.post(poll)
        HomeGeo.glog(this, "poll: started")
        return START_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacks(poll)
        try { netCb?.let { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } } catch (e: Exception) { }
        netCb = null
        HomeGeo.glog(this, "poll: stopped")
        super.onDestroy()
    }
    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("geo_poll", "Home monitor", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "geo_poll")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("RelayX")
            .setContentText("Home arrival active")
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(7003, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(7003, n)
    }
    @SuppressLint("MissingPermission")
    private fun checkLocation() {
        try {
            val cfg = HomeGeo.load(this)
            if (cfg == null || !cfg.enabled) { stopSelf(); return }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                HomeGeo.glog(this, "poll: no location permission")
                return
            }
            LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc == null) { HomeGeo.glog(this, "poll: no location"); return@addOnSuccessListener }
                    val r = FloatArray(1)
                    Location.distanceBetween(loc.latitude, loc.longitude, cfg.lat, cfg.lon, r)
                    val inside = r[0] <= cfg.radius
                    HomeGeo.glog(this, "poll: " + r[0].toInt() + "m inside=" + inside + " wasIn=" + wasInside)
                    if (!wasInside && inside) {
                        HomeGeo.glog(this, "poll: entered, trigger scene")
                        HomeGeo.notifyArrived(this)
                    }
                    if (inside) HomeGeo.retryPending(this, "poll")
                    wasInside = inside
                }
                .addOnFailureListener { e ->
                    HomeGeo.glog(this, "poll: loc err " + (e.message ?: "?"))
                }
        } catch (e: Exception) {
            HomeGeo.glog(this, "poll: err " + (e.message ?: "?"))
        }
    }
    companion object {
        fun start(ctx: Context): Boolean = try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, GeoPollService::class.java))
            true
        } catch (e: Exception) { false }
        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, GeoPollService::class.java)) } catch (e: Exception) {} }
    }
}
