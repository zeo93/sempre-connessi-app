package it.sempreconnessi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Legge lo stato dal server di casa.
 *
 * - Con il telefono sul Wi-Fi di casa: usa l'indirizzo impostato (192.168.1.2:8090) e
 *   lega la connessione alla rete Wi-Fi, cosi' funziona anche quando Android
 *   segna il Wi-Fi "senza internet" (la fibra e' giu').
 * - Con il telefono in hotspot: il server e' un client dell'hotspot con un indirizzo
 *   che cambia; l'app lo cerca sulla rete dell'hotspot e se lo ricorda.
 */
class Client(private val ctx: Context) {
    private val imp = Impostazioni(ctx)
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)!!

    @Volatile private var ultimaRicerca = 0L

    fun wifi(): Network? {
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /**
     * @param attendi se non null, il server risponde quando la versione cambia (o dopo maxS secondi)
     */
    @Throws(IOException::class)
    fun leggi(attendi: Int? = null, maxS: Int = 50): Stato {
        var ultimoErrore: Exception? = null
        val wifi = wifi()
        val candidati = buildList {
            add(imp.host to wifi)
            imp.hostHotspot?.let { add(it to null) }
        }
        for ((host, rete) in candidati) {
            try {
                return richiesta(host, rete, attendi, maxS)
            } catch (e: Exception) {
                ultimoErrore = e
            }
        }
        // Nessuna risposta: forse il telefono fa da hotspot. Cerca il server (al massimo ogni 2 minuti).
        val ora = System.currentTimeMillis()
        if (ora - ultimaRicerca > 120_000) {
            ultimaRicerca = ora
            val trovato = cercaSuHotspot()
            if (trovato != null) {
                imp.hostHotspot = trovato
                return richiesta(trovato, null, attendi, maxS)
            }
        }
        throw IOException("server non raggiungibile", ultimoErrore)
    }

    private fun richiesta(host: String, rete: Network?, attendi: Int?, maxS: Int): Stato {
        val q = if (attendi != null) "?attendi=$attendi&max=$maxS" else ""
        val url = URL("http://$host/stato$q")
        val conn = (if (rete != null) rete.openConnection(url) else url.openConnection()) as HttpURLConnection
        try {
            conn.connectTimeout = 4000
            conn.readTimeout = (if (attendi != null) maxS + 15 else 8) * 1000
            conn.useCaches = false
            if (imp.token.isNotEmpty()) conn.setRequestProperty("X-Token", imp.token)
            val codice = conn.responseCode
            if (codice == 401) throw IOException("token sbagliato")
            if (codice != 200) throw IOException("risposta HTTP $codice")
            val testo = conn.inputStream.bufferedReader().use { it.readText() }
            return Stato.daJson(testo, host)
        } finally {
            conn.disconnect()
        }
    }

    /** Cerca la porta del server sulle reti locali che non sono il Wi-Fi di casa (es. hotspot). */
    private fun cercaSuHotspot(): String? {
        val porta = imp.porta
        val bersagli = mutableListOf<String>()
        val esclusi = listOf("lo", "rmnet", "ccmni", "v4-", "dummy", "tun", "ipsec", "clat", "seth")
        for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
            if (!ni.isUp || ni.isLoopback || esclusi.any { ni.name.startsWith(it) }) continue
            for (ia in ni.interfaceAddresses) {
                val a = ia.address as? Inet4Address ?: continue
                if (!a.isSiteLocalAddress) continue
                if (a.hostAddress == imp.host.substringBefore(':')) continue
                // si scansiona al massimo un /24 attorno al proprio indirizzo
                val b = a.address
                val proprio = b[3].toInt() and 0xff
                val base = "${b[0].toInt() and 0xff}.${b[1].toInt() and 0xff}.${b[2].toInt() and 0xff}."
                for (i in 1..254) if (i != proprio) bersagli += base + i
            }
        }
        if (bersagli.isEmpty()) return null
        val trovato = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(48)
        try {
            for (ip in bersagli) pool.execute {
                if (trovato.get() != null) return@execute
                try {
                    Socket().use { it.connect(InetSocketAddress(ip, porta), 400) }
                    val host = "$ip:$porta"
                    richiesta(host, null, null, 0)  // e' davvero il nostro server?
                    trovato.compareAndSet(null, host)
                } catch (_: Exception) {
                }
            }
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        return trovato.get()
    }
}
