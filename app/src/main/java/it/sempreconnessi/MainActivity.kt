package it.sempreconnessi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Schermata principale: stato della linea, dettagli, storico guasti, impostazioni. */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var imp: Impostazioni
    private lateinit var client: Client

    private lateinit var riquadro: LinearLayout
    private lateinit var titolo: TextView
    private lateinit var sottotitolo: TextView
    private lateinit var dettagli: TextView
    private lateinit var storico: TextView
    private lateinit var errore: TextView

    @Volatile private var visibile = false

    private val aggiorna = object : Runnable {
        override fun run() {
            if (!visibile) return
            thread(name = "lettura-schermata") {
                try {
                    MonitorService.pubblica(client.leggi())
                } catch (e: Exception) {
                    handler.post { mostraErrore(e.message ?: e.toString()) }
                }
            }
            handler.postDelayed(this, 5_000)
        }
    }

    private val suCambio: () -> Unit = { handler.post { mostra() } }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        imp = Impostazioni(this)
        client = Client(this)
        Notifiche.creaCanali(this)
        setContentView(costruisci())

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        MonitorService.avvia(this)
    }

    override fun onResume() {
        super.onResume()
        visibile = true
        MonitorService.ascolta(suCambio)
        mostra()
        handler.post(aggiorna)
    }

    override fun onPause() {
        visibile = false
        MonitorService.smetti(suCambio)
        handler.removeCallbacks(aggiorna)
        super.onPause()
    }

    // ------------------------------------------------------------------ interfaccia
    private fun costruisci(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        riquadro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        titolo = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        sottotitolo = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
        }
        riquadro.addView(titolo)
        riquadro.addView(sottotitolo)
        col.addView(riquadro)

        errore = TextView(this).apply {
            setTextColor(Color.rgb(0xC6, 0x28, 0x28))
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        col.addView(errore)

        col.addView(intestazione("Linea"))
        dettagli = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setLineSpacing(0f, 1.25f) }
        col.addView(dettagli)

        col.addView(intestazione("Ultimi guasti"))
        storico = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setLineSpacing(0f, 1.25f) }
        col.addView(storico)

        col.addView(intestazione("Azioni"))
        col.addView(bottone("Apri impostazioni hotspot") { startActivity(Notifiche.intentHotspot(this)) })
        col.addView(bottone("Togli il risparmio batteria per l'app") { chiediBatteria() })
        col.addView(bottone("Prova notifica") {
            Notifiche.linea(this, "Prova", "Così arriva l'avviso di fibra giù.", allarme = true, conHotspot = true)
        })

        col.addView(intestazione("Impostazioni"))
        col.addView(etichetta("Indirizzo del server (IP:porta)"))
        val campoHost = EditText(this).apply {
            setText(imp.host)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        col.addView(campoHost)
        col.addView(etichetta("Token (vuoto se sul server non è impostato)"))
        val campoToken = EditText(this).apply {
            setText(imp.token)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        col.addView(campoToken)
        val avvisa = CheckBox(this).apply {
            text = "Avvisami se il server non risponde (solo sul Wi-Fi)"
            isChecked = imp.avvisaIrraggiungibile
        }
        col.addView(avvisa)
        col.addView(bottone("Salva") {
            imp.host = campoHost.text.toString()
            imp.token = campoToken.text.toString()
            imp.avvisaIrraggiungibile = avvisa.isChecked
            Toast.makeText(this, "Salvato", Toast.LENGTH_SHORT).show()
            MonitorService.avvia(this)
        })

        col.addView(TextView(this).apply {
            text = "Per MacroDroid: trigger \"Intent ricevuto\", azione ${MonitorService.AZIONE_STATO}, " +
                "extra \"stato\" = NORMAL, DOWN, BACKUP o FERMO."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(16), 0, 0)
            alpha = 0.7f
        })

        return ScrollView(this).apply { addView(col) }
    }

    private fun intestazione(t: String) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun etichetta(t: String) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(8), 0, 0)
    }

    private fun bottone(t: String, azione: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        gravity = Gravity.CENTER
        setOnClickListener { azione() }
    }

    private fun sfondo(colore: Int) = GradientDrawable().apply {
        setColor(colore)
        cornerRadius = dp(16).toFloat()
    }

    // ------------------------------------------------------------------ dati
    private fun mostraErrore(msg: String) {
        if (MonitorService.ultimo == null) {
            titolo.text = "Server non raggiungibile"
            sottotitolo.text = imp.host
            riquadro.background = sfondo(Color.rgb(0x61, 0x61, 0x61))
        }
        errore.text = "Ultima lettura non riuscita: $msg"
        errore.visibility = View.VISIBLE
    }

    private fun mostra() {
        val s = MonitorService.ultimo
        if (s == null) {
            titolo.text = "Connessione al server..."
            sottotitolo.text = imp.host
            riquadro.background = sfondo(Color.rgb(0x61, 0x61, 0x61))
            dettagli.text = ""
            storico.text = ""
            MonitorService.ultimoErrore?.let { mostraErrore(it) }
            return
        }
        val vecchio = System.currentTimeMillis() - s.letto > 90_000
        errore.visibility = View.GONE
        MonitorService.ultimoErrore?.let { if (vecchio) mostraErrore(it) }

        titolo.text = Testi.nomeStato(s.stato)
        sottotitolo.text = when (s.stato) {
            "NORMAL" -> "da ${Testi.durata(s.daSecondi)}"
            "DOWN" -> "da ${Testi.durata(s.guastoDaSecondi ?: s.daSecondi)} · attiva l'hotspot"
            "BACKUP" -> "guasto da ${Testi.durata(s.guastoDaSecondi ?: s.daSecondi)} · casa sul telefono"
            else -> ""
        }
        riquadro.background = sfondo(when (s.stato) {
            "NORMAL" -> Color.rgb(0x2E, 0x7D, 0x32)
            "BACKUP" -> Color.rgb(0xEF, 0x6C, 0x00)
            "DOWN", "FERMO" -> Color.rgb(0xC6, 0x28, 0x28)
            else -> Color.rgb(0x61, 0x61, 0x61)
        })
        if (vecchio) riquadro.alpha = 0.6f else riquadro.alpha = 1f

        val ora = SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(Date(s.letto))
        dettagli.text = buildString {
            appendLine("Ping sulla fibra: ${Testi.ms(s.pingFibraMs)}")
            if (s.stato != "NORMAL") appendLine("Ping attuale: ${Testi.ms(s.pingInternetMs)}")
            s.fibraPerditaPct?.let { appendLine("Ping persi (ultimi 10 min): $it%") }
            if (s.inGuasto) {
                appendLine("Telefono: ${s.telefonoInterfaccia ?: "non collegato"}")
                appendLine("Giga usati in questo guasto: ${Testi.mb(s.guastoMbBackup)}")
            }
            appendLine("Giga usati in backup questo mese: ${Testi.mb(s.mbBackupMese)}")
            appendLine("Server acceso da: ${Testi.durata(s.serverUptimeS)}")
            append("Letto alle $ora da ${s.host}")
        }
        storico.text = if (s.storico.isEmpty()) "Nessun guasto registrato." else
            s.storico.take(10).joinToString("\n") { g ->
                "${Testi.data(g.inizio)} · ${Testi.durata(g.durataS)}" +
                    (if (g.backup) " · backup ${Testi.mb(g.mbBackup)}" else " · senza backup")
            }
    }

    private fun chiediBatteria() {
        val pm = getSystemService(PowerManager::class.java)!!
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Già fatto: l'app non è limitata", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
