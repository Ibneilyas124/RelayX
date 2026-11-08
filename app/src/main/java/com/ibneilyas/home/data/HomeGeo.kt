package com.ibneilyas.home.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import com.ibneilyas.home.GeoRunService
import com.ibneilyas.home.GeoPollService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.ibneilyas.home.GeoReceiver
import com.ibneilyas.home.MainActivity

data class GeoCfg(
    val enabled: Boolean,
    val lat: Double,
    val lon: Double,
    val radius: Float,
    val sceneId: String,
    val sceneName: String
)

// Ghar aate hi: ghar ke ilaqe mein dakhil hone par notification, tap par Scene chale (Scene chalana app ka kaam hai)
object HomeGeo {
    private const val CH = "ibn_arrive"
    private const val COOLDOWN_MS = 30L * 60L * 1000L

    private fun sp(ctx: Context) = ctx.getSharedPreferences("geo", Context.MODE_PRIVATE)

    // Chhota log (aakhri 12 lines): geofence ke saath asal mein kya hua
    fun glog(ctx: Context, text: String) {
        try {
            val t = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val old = sp(ctx).getString("glog", "") ?: ""
            val lines = (old.split("\n").filter { it.isNotEmpty() } + "$t $text").takeLast(60)
            sp(ctx).edit().putString("glog", lines.joinToString("\n")).apply()
        } catch (e: Exception) { }
    }

    fun logText(ctx: Context): String = sp(ctx).getString("glog", "") ?: ""

    fun load(ctx: Context): GeoCfg? {
        val p = sp(ctx)
        val lat = p.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lon = p.getString("lon", null)?.toDoubleOrNull() ?: return null
        return GeoCfg(
            p.getBoolean("on", false), lat, lon,
            p.getFloat("radius", 150f),
            p.getString("sid", "") ?: "",
            p.getString("sname", "") ?: ""
        )
    }

    fun save(ctx: Context, c: GeoCfg) {
        val r = if (c.radius < 100f) 100f else if (c.radius > 1000f) 1000f else c.radius
        sp(ctx).edit()
            .putBoolean("on", c.enabled)
            .putString("lat", c.lat.toString())
            .putString("lon", c.lon.toString())
            .putFloat("radius", r)
            .putString("sid", c.sceneId)
            .putString("sname", c.sceneName)
            .apply()
    }

    // Natija: "regat" (kab kamyab), "regerr" (kya galti) prefs mein, taake screen dikha sake
    fun status(ctx: Context): String {
        val p = sp(ctx)
        val err = p.getString("regerr", null)
        if (err != null) return "Error: $err"
        val at = p.getLong("regat", 0L)
        return if (at > 0L) "Registered " + java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(at)) else "Not registered"
    }

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, GeoReceiver::class.java)
        var f = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 31) f = f or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(ctx, 0, i, f)
    }

    // true = request bheji gayi (asli natija status() mein)
    @SuppressLint("MissingPermission")
    fun register(ctx: Context): Boolean {
        val c = load(ctx) ?: return false
        if (!c.enabled) return false
        val ava = com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx)
        glog(ctx, "register: play services " + (if (ava == 0) "OK" else "code " + ava))
        return try {
            val g = Geofence.Builder()
                .setRequestId("home")
                .setCircularRegion(c.lat, c.lon, c.radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
            val req = GeofencingRequest.Builder()
                .setInitialTrigger(0)
                .addGeofence(g)
                .build()
            LocationServices.getGeofencingClient(ctx).addGeofences(req, pending(ctx))
                .addOnSuccessListener {
                    sp(ctx).edit().putLong("regat", System.currentTimeMillis()).remove("regerr").apply()
                    glog(ctx, "register OK")
                    GeoPollService.start(ctx)
                }
                .addOnFailureListener { e ->
                    glog(ctx, "register ERR " + (e.message ?: "unknown"))
                    sp(ctx).edit().putString("regerr", e.message ?: "unknown").apply()
                }
            true
        } catch (e: Exception) {
            sp(ctx).edit().putString("regerr", e.message ?: "unknown").apply()
            false
        }
    }

    fun unregister(ctx: Context) {
        try {
            LocationServices.getGeofencingClient(ctx).removeGeofences(pending(ctx))
            sp(ctx).edit().remove("regat").apply()
            GeoPollService.stop(ctx)
        } catch (e: Exception) { }
    }

    fun isAuto(ctx: Context): Boolean = sp(ctx).getBoolean("auto", true)
    fun setAuto(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean("auto", v).apply() }
    fun clearLast(ctx: Context) { sp(ctx).edit().remove("last").apply() }

    // Arrival par scene fail ho to 20 minute tak dobara koshish ka nishan
    fun pendingActive(ctx: Context): Boolean {
        val t = sp(ctx).getLong("pend", 0L)
        if (t <= 0L) return false
        if (System.currentTimeMillis() - t > 20L * 60L * 1000L) {
            sp(ctx).edit().remove("pend").apply()
            glog(ctx, "retry: window over")
            return false
        }
        return true
    }

    fun setPending(ctx: Context) {
        if (!pendingActive(ctx)) {
            sp(ctx).edit().putLong("pend", System.currentTimeMillis()).apply()
            glog(ctx, "retry: pending set (20 min)")
        }
    }

    fun clearPending(ctx: Context) { sp(ctx).edit().remove("pend").apply() }

    fun retryPending(ctx: Context, why: String) {
        if (!pendingActive(ctx)) return
        if (GeoRunService.running) return
        val c = load(ctx) ?: return
        if (!c.enabled || !isAuto(ctx)) return
        glog(ctx, "retry: starting scene again (" + why + ")")
        if (!GeoRunService.start(ctx)) glog(ctx, "retry: could not start service")
    }

    fun justRegistered(ctx: Context): Boolean {
        val t = sp(ctx).getLong("regat", 0L)
        return t > 0L && System.currentTimeMillis() - t < 15000L
    }

    fun notifyArrived(ctx: Context, allowAuto: Boolean = true) {
        try {
            val c = load(ctx) ?: return
            if (!c.enabled || c.sceneId.isEmpty()) { glog(ctx, "notify: skipped, disabled or no scene"); return }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) { glog(ctx, "notify: skipped, no notification permission"); return }
            val p = sp(ctx)
            val now = System.currentTimeMillis()
            if (now - p.getLong("last", 0L) < COOLDOWN_MS) { glog(ctx, "notify: skipped, 30 min cooldown"); return }
            p.edit().putLong("last", now).apply()
            if (allowAuto && isAuto(ctx) && GeoRunService.start(ctx)) { glog(ctx, "notify: service started"); return }
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH, "Home arrival", NotificationManager.IMPORTANCE_HIGH))
            val open = Intent(ctx, MainActivity::class.java)
            open.putExtra("geoScene", c.sceneId)
            open.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            val pi = PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Welcome home")
                .setContentText("Tap to run: " + c.sceneName)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(7001, n)
            glog(ctx, "notify: fallback notification posted")
        } catch (e: Exception) { }
    }
}
