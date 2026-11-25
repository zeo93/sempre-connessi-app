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

    /** Solo se il repository GitHub e' privato. */
    var tokenGithub: String
        get() = p.getString("token_github", "")!!
        set(v) = p.edit().putString("token_github", v.trim()).apply()

    var ultimoControlloAggiornamenti: Long
        get() = p.getLong("ultimo_controllo_agg", 0L)
        set(v) = p.edit().putLong("ultimo_controllo_agg", v).apply()

    var versioneGiaAvvisata: Int
        get() = p.getInt("versione_avvisata", 0)
        set(v) = p.edit().putInt("versione_avvisata", v).apply()

    val porta: Int get() = host.substringAfter(':', "8090").toIntOrNull() ?: 8090
}
