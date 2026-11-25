package it.sempreconnessi

import android.content.Context
import android.content.SharedPreferences

/** Impostazioni salvate sul telefono. */
class Impostazioni(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("impostazioni", Context.MODE_PRIVATE)

    var host: String
        get() = p.getString("host", "192.168.1.2:8090")!!
        set(v) = p.edit().putString("host", v.trim()).apply()

    var token: String
        get() = p.getString("token", "")!!
        set(v) = p.edit().putString("token", v.trim()).apply()

    /** Indirizzo del server trovato sulla rete dell'hotspot (cambia a ogni accensione). */
    var hostHotspot: String?
        get() = p.getString("host_hotspot", null)
        set(v) = p.edit().putString("host_hotspot", v).apply()

    var avvisaIrraggiungibile: Boolean
        get() = p.getBoolean("avvisa_irraggiungibile", true)
        set(v) = p.edit().putBoolean("avvisa_irraggiungibile", v).apply()

    /** Ultimo stato notificato, per non ripetere gli avvisi dopo un riavvio dell'app. */
    var ultimoStato: String?
        get() = p.getString("ultimo_stato", null)
        set(v) = p.edit().putString("ultimo_stato", v).apply()

    var ultimoStatoDal: String?
        get() = p.getString("ultimo_stato_dal", null)
        set(v) = p.edit().putString("ultimo_stato_dal", v).apply()

    val porta: Int get() = host.substringAfter(':', "8090").toIntOrNull() ?: 8090
}
