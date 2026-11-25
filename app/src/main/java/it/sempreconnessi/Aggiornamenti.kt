package it.sempreconnessi

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Una versione pubblicata nelle Releases di GitHub. */
data class Versione(val numero: Int, val nome: String, val urlApk: String, val note: String)

/**
 * Aggiornamenti dell'app dalle Releases del repository GitHub.
 * Il numero di versione e' il numero della compilazione su GitHub Actions (tag v1.0.N).
 */
object Aggiornamenti {
    private const val TAG = "SempreConnessi"
    const val REPOSITORY = "zeo93/sempre-connessi-app"
    const val CONTROLLA_OGNI_MS = 6 * 60 * 60 * 1000L
    private const val ID_NOTIFICA = 4
    const val EXTRA_AGGIORNA = "aggiorna"

    fun versioneInstallata(ctx: Context): Int {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pi.versionCode
    }

    private fun apri(url: String, token: String, accept: String, conAuth: Boolean = true): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = false
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "sempre-connessi-app")
        if (conAuth && token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    /** Ultima versione pubblicata, o null se non ce ne sono. */
    @Throws(IOException::class)
    fun ultima(ctx: Context): Versione? {
        val token = Impostazioni(ctx).tokenGithub
        val c = apri("https://api.github.com/repos/$REPOSITORY/releases/latest", token, "application/vnd.github+json")
        try {
            when (c.responseCode) {
                200 -> {}
                404 -> if (token.isEmpty()) throw IOException("repository privato: serve il token GitHub")
                       else return null
                401 -> throw IOException("token GitHub non valido o scaduto")
                else -> throw IOException("GitHub ha risposto ${c.responseCode}")
            }
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = j.optString("tag_name")
            val numero = tag.substringAfterLast('.').toIntOrNull() ?: return null
            val assets = j.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return Versione(numero, tag, a.optString("url"), j.optString("body"))
                }
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    /** Scarica l'APK e lo passa all'installatore di Android. Da chiamare fuori dal thread principale. */
    @Throws(IOException::class)
    fun installa(ctx: Context, v: Versione) {
        val token = Impostazioni(ctx).tokenGithub
        var c = apri(v.urlApk, token, "application/octet-stream")
        // GitHub risponde con un rinvio a un indirizzo firmato: li' il token non va mandato
        var rinvii = 0
        while (c.responseCode in 301..308 && rinvii++ < 5) {
            val dove = c.getHeaderField("Location") ?: break
            c.disconnect()
            c = apri(dove, token, "application/octet-stream", conAuth = false)
        }
        try {
            if (c.responseCode != 200) throw IOException("download non riuscito (${c.responseCode})")
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                // su Android 12+ gli aggiornamenti successivi possono non chiedere conferma
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { s ->
                s.openWrite("app.apk", 0, -1).use { out ->
                    c.inputStream.use { it.copyTo(out) }
                    s.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(ctx, id,
                    Intent(ctx, EsitoInstallazione::class.java), flags)
                s.commit(pi.intentSender)
            }
        } finally {
            c.disconnect()
        }
    }

    /** Android chiede un permesso apposta per installare app: true se gia' dato. */
    fun puoInstallare(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun intentPermessoInstallazione(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    fun notificaDisponibile(ctx: Context, v: Versione) {
        val pi = PendingIntent.getActivity(ctx, 2,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_AGGIORNA, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        notifica(ctx, "Aggiornamento disponibile", "Versione ${v.nome.removePrefix("v")}: tocca per installarla.", pi)
    }

    fun notifica(ctx: Context, titolo: String, testo: String, pi: PendingIntent?) {
        val b = Notification.Builder(ctx, Notifiche.CANALE_INFO)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(titolo)
            .setContentText(testo)
            .setStyle(Notification.BigTextStyle().bigText(testo))
            .setAutoCancel(true)
        if (pi != null) b.setContentIntent(pi)
        ctx.getSystemService(NotificationManager::class.java)!!.notify(ID_NOTIFICA, b.build())
    }

    /** Controllo periodico dal servizio: avvisa una volta per ogni versione nuova. */
    fun controllaInBackground(ctx: Context) {
        val imp = Impostazioni(ctx)
        val ora = System.currentTimeMillis()
        if (ora - imp.ultimoControlloAggiornamenti < CONTROLLA_OGNI_MS) return
        imp.ultimoControlloAggiornamenti = ora
        try {
            val v = ultima(ctx) ?: return
            if (v.numero > versioneInstallata(ctx) && v.numero != imp.versioneGiaAvvisata) {
                imp.versioneGiaAvvisata = v.numero
                notificaDisponibile(ctx, v)
            }
        } catch (e: Exception) {
            Log.w(TAG, "controllo aggiornamenti non riuscito: $e")
        }
    }
}

/** Riceve l'esito dell'installazione dall'installatore di Android. */
class EsitoInstallazione : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val stato = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (stato) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val conferma = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                conferma.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    ctx.startActivity(conferma)
                } catch (e: Exception) {
                    val pi = PendingIntent.getActivity(ctx, 3, conferma,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    Aggiornamenti.notifica(ctx, "Aggiornamento pronto", "Tocca per confermare l'installazione.", pi)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}  // l'app viene riavviata da MY_PACKAGE_REPLACED
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                Aggiornamenti.notifica(ctx, "Aggiornamento non installato",
                    "La versione nuova è firmata con una chiave diversa: disinstalla l'app e installa " +
                        "a mano l'ultima versione da GitHub. Dopo, gli aggiornamenti funzioneranno da soli.", null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> {}  // annullato dall'utente
            else -> Aggiornamenti.notifica(ctx, "Aggiornamento non installato",
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Errore $stato", null)
        }
    }
}
