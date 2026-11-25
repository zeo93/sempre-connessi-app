package it.sempreconnessi

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Servizio in primo piano: resta in ascolto del server (richiesta lunga che il
 * server chiude appena lo stato cambia) e manda le notifiche.
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "SempreConnessi"
        const val AZIONE_STATO = "it.sempreconnessi.STATO"   // per MacroDroid/Tasker
        private const val SVEGLIA_MS = 15 * 60 * 1000L

        @Volatile var inEsecuzione = false
            private set

        /** Ultimo stato letto, per la schermata principale. */
        @Volatile var ultimo: Stato? = null
            private set
        @Volatile var ultimoErrore: String? = null
            private set
        private val ascoltatori = mutableSetOf<() -> Unit>()

        fun ascolta(f: () -> Unit) = synchronized(ascoltatori) { ascoltatori += f }
        fun smetti(f: () -> Unit) = synchronized(ascoltatori) { ascoltatori -= f }
        private fun avvisaAscoltatori() = synchronized(ascoltatori) { ascoltatori.toList() }.forEach { it() }

        fun pubblica(s: Stato) {
            ultimo = s
            ultimoErrore = null
            avvisaAscoltatori()
        }

        fun avvia(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, MonitorService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "impossibile avviare il servizio: $e")
            }
        }
    }

    private lateinit var imp: Impostazioni
    private lateinit var client: Client
    private var thread: Thread? = null
    @Volatile private var attivo = false
    private var irraggiungibileDa = 0L
    private var avvisatoIrraggiungibile = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        imp = Impostazioni(this)
        client = Client(this)
        Notifiche.creaCanali(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifiche.servizio(this, "In ascolto del server di casa")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifiche.ID_SERVIZIO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifiche.ID_SERVIZIO, n)
        }
        inEsecuzione = true
        if (thread?.isAlive != true) {
            attivo = true
            thread = Thread(::ciclo, "ascolto-server").apply { isDaemon = true; start() }
        } else {
            thread?.interrupt()  // sveglia: rifai subito la richiesta
        }
        programmaSveglia()
        Thread({ Aggiornamenti.controllaInBackground(this) }, "aggiornamenti").start()
        return START_STICKY
    }

    override fun onDestroy() {
        attivo = false
        inEsecuzione = false
        thread?.interrupt()
        super.onDestroy()
    }

    /** Sveglia periodica: se la connessione si e' bloccata in silenzio, la riapre. */
    private fun programmaSveglia() {
        val am = getSystemService(AlarmManager::class.java)!!
        val pi = PendingIntent.getBroadcast(this, 0,
            Intent(this, AvvioReceiver::class.java).setAction(AvvioReceiver.SVEGLIA),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + SVEGLIA_MS, pi)
    }

    private fun ciclo() {
        val pm = getSystemService(PowerManager::class.java)!!
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SempreConnessi:lettura")
        var errori = 0
        var versione: Int? = null  // propria del servizio: la schermata legge per conto suo
        while (attivo) {
            try {
                val s = client.leggi(attendi = versione, maxS = 50)
                wl.acquire(10_000)
                try {
                    errori = 0
                    versione = s.versione
                    gestisci(s)
                } finally {
                    if (wl.isHeld) wl.release()
                }
            } catch (_: InterruptedException) {
                if (!attivo) return
            } catch (e: Exception) {
                errori++
                ultimoErrore = e.message ?: e.toString()
                avvisaAscoltatori()
                gestisciErrore()
                try {
                    Thread.sleep(minOf(5_000L * errori, 30_000L))
                } catch (_: InterruptedException) {
                    if (!attivo) return
                }
            }
        }
    }

    private fun gestisci(s: Stato) {
        val prima = imp.ultimoStato
        val primaDal = imp.ultimoStatoDal
        pubblica(s)

        if (irraggiungibileDa != 0L) {
            irraggiungibileDa = 0L
            if (avvisatoIrraggiungibile) {
                avvisatoIrraggiungibile = false
                Notifiche.togliServer(this)
            }
        }

        aggiornaNotificaFissa(s)

        if (s.stato == prima && s.dal == primaDal) return
        imp.ultimoStato = s.stato
        imp.ultimoStatoDal = s.dal

        // per MacroDroid: trigger "Intent ricevuto" con azione it.sempreconnessi.STATO
        sendBroadcast(Intent(AZIONE_STATO).putExtra("stato", s.stato).putExtra("dal", s.dal))

        when (s.stato) {
            "DOWN" -> Notifiche.linea(this, "Fibra giù",
                "Internet di casa non funziona. Attiva l'hotspot del telefono per tornare online.",
                allarme = true, conHotspot = true)
            "BACKUP" -> Notifiche.linea(this, "Backup attivo",
                "La casa naviga con la connessione del telefono. Occhio ai giga.",
                allarme = false, conHotspot = false)
            "NORMAL" -> if (prima == "DOWN" || prima == "BACKUP") {
                val g = s.storico.firstOrNull()
                val dettagli = if (g != null) {
                    " Guasto durato ${Testi.durata(g.durataS)}" +
                        (if (g.backup) ", ${Testi.mb(g.mbBackup)} usati dal telefono." else ".")
                } else ""
                val hotspot = if (prima == "BACKUP") " Puoi spegnere l'hotspot." else ""
                Notifiche.linea(this, "Fibra tornata", "La casa è di nuovo sulla fibra.$dettagli$hotspot",
                    allarme = false, conHotspot = false)
            }
            "FERMO" -> Notifiche.linea(this, "Servizio fermo",
                "Sul server il servizio sempre-connessi non è in esecuzione: il cambio linea automatico non funziona.",
                allarme = true, conHotspot = false)
        }
    }

    private fun aggiornaNotificaFissa(s: Stato) {
        val testo = buildString {
            append(Testi.nomeStato(s.stato))
            if (s.stato == "NORMAL" && s.pingFibraMs != null) append(" · ping ${Testi.ms(s.pingFibraMs)}")
            if (s.inGuasto) append(" da ${Testi.durata(s.guastoDaSecondi ?: s.daSecondi)}")
        }
        getSystemService(NotificationManager::class.java)!!
            .notify(Notifiche.ID_SERVIZIO, Notifiche.servizio(this, testo))
    }

    private fun gestisciErrore() {
        val ora = System.currentTimeMillis()
        if (irraggiungibileDa == 0L) {
            irraggiungibileDa = ora
            getSystemService(NotificationManager::class.java)!!
                .notify(Notifiche.ID_SERVIZIO, Notifiche.servizio(this, "Server non raggiungibile"))
        }
        // avvisa solo se il telefono e' su un Wi-Fi (fuori casa e' normale non raggiungerlo)
        if (imp.avvisaIrraggiungibile && !avvisatoIrraggiungibile &&
            ora - irraggiungibileDa > 3 * 60_000 && client.wifi() != null
        ) {
            avvisatoIrraggiungibile = true
            Notifiche.server(this, "Server non raggiungibile",
                "Il server di casa non risponde da qualche minuto. È acceso?")
        }
    }
}
