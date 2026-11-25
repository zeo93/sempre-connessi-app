package it.sempreconnessi

import org.json.JSONObject

/** Un guasto concluso, dallo storico del server. */
data class Guasto(
    val inizio: String,
    val fine: String,
    val durataS: Long,
    val backup: Boolean,
    val mbBackup: Double,
)

/** Lo stato della linea come lo restituisce GET /stato sul server. */
data class Stato(
    val versione: Int,
    val stato: String,              // NORMAL, DOWN, BACKUP, FERMO, SCONOSCIUTO
    val dal: String?,
    val daSecondi: Long?,
    val demoneAttivo: Boolean,
    val telefonoInterfaccia: String?,
    val pingFibraMs: Double?,
    val pingInternetMs: Double?,
    val fibraPerditaPct: Int?,
    val guastoDaSecondi: Long?,
    val guastoMbBackup: Double?,
    val mbBackupMese: Double,
    val storico: List<Guasto>,
    val serverUptimeS: Long?,
    val letto: Long = System.currentTimeMillis(),
    val host: String = "",
) {
    val inGuasto: Boolean get() = stato == "DOWN" || stato == "BACKUP"

    companion object {
        private fun JSONObject.dbl(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k)
        private fun JSONObject.lng(k: String): Long? = if (isNull(k) || !has(k)) null else optLong(k)
        private fun JSONObject.str(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)

        fun daJson(testo: String, host: String): Stato {
            val j = JSONObject(testo)
            require(j.optString("app") == "sempre-connessi") { "risposta non del server sempre-connessi" }
            val tel = j.optJSONObject("telefono")
            val g = j.optJSONObject("guasto")
            val arr = j.optJSONArray("storico")
            val storico = buildList {
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(Guasto(
                        o.optString("inizio"), o.optString("fine"), o.optLong("durata_s"),
                        o.optBoolean("backup"), o.optDouble("mb_backup", 0.0),
                    ))
                }
            }
            return Stato(
                versione = j.optInt("versione"),
                stato = j.optString("stato", "SCONOSCIUTO"),
                dal = j.str("dal"),
                daSecondi = j.lng("da_secondi"),
                demoneAttivo = j.optBoolean("demone_attivo"),
                telefonoInterfaccia = tel?.str("interfaccia"),
                pingFibraMs = j.dbl("ping_fibra_ms"),
                pingInternetMs = j.dbl("ping_internet_ms"),
                fibraPerditaPct = j.lng("fibra_perdita_pct")?.toInt(),
                guastoDaSecondi = g?.lng("da_secondi"),
                guastoMbBackup = g?.dbl("mb_backup"),
                mbBackupMese = j.optDouble("mb_backup_mese", 0.0),
                storico = storico,
                serverUptimeS = j.lng("server_uptime_s"),
                host = host,
            )
        }
    }
}

object Testi {
    fun nomeStato(s: String): String = when (s) {
        "NORMAL" -> "Fibra OK"
        "DOWN" -> "Fibra giù"
        "BACKUP" -> "Backup attivo"
        "FERMO" -> "Servizio fermo sul server"
        else -> "Stato sconosciuto"
    }

    fun durata(sec: Long?): String {
        if (sec == null || sec < 0) return "-"
        val g = sec / 86400
        val h = (sec % 86400) / 3600
        val m = (sec % 3600) / 60
        return when {
            g > 0 -> "$g g $h h"
            h > 0 -> "$h h $m min"
            m > 0 -> "$m min"
            else -> "$sec s"
        }
    }

    fun ms(v: Double?): String = if (v == null) "nessuna risposta" else "${"%.0f".format(v)} ms"

    fun mb(v: Double?): String = when {
        v == null -> "-"
        v >= 1000 -> "${"%.2f".format(v / 1000)} GB"
        else -> "${"%.0f".format(v)} MB"
    }

    /** "2026-11-25T13:00:00+01:00" -> "25/11 13:00" */
    fun data(iso: String?): String {
        if (iso.isNullOrBlank() || iso.length < 16) return "-"
        return "${iso.substring(8, 10)}/${iso.substring(5, 7)} ${iso.substring(11, 16)}"
    }
}
