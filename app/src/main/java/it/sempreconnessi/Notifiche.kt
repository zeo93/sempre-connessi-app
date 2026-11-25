package it.sempreconnessi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.provider.Settings

object Notifiche {
    const val CANALE_ALLARMI = "allarmi"
    const val CANALE_INFO = "info"
    const val CANALE_SERVIZIO = "servizio"

    const val ID_SERVIZIO = 1
    const val ID_LINEA = 2
    const val ID_SERVER = 3

    fun creaCanali(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(NotificationChannel(
            CANALE_ALLARMI, "Fibra giù", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Avviso quando la linea di casa cade: va attivato l'hotspot"
            enableVibration(true)
            enableLights(true)
            lightColor = Color.RED
        })
        nm.createNotificationChannel(NotificationChannel(
            CANALE_INFO, "Cambi di stato", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Backup attivo, fibra tornata, server non raggiungibile"
        })
        nm.createNotificationChannel(NotificationChannel(
            CANALE_SERVIZIO, "Controllo in corso", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Notifica fissa richiesta da Android per restare in ascolto"
            setShowBadge(false)
        })
    }

    private fun apriApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Intent per la schermata dell'hotspot (il nome cambia da produttore a produttore). */
    fun intentHotspot(ctx: Context): Intent {
        val tether = Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (tether.resolveActivity(ctx.packageManager) != null) tether
        else Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun azioneHotspot(ctx: Context): Notification.Action {
        val pi = PendingIntent.getActivity(ctx, 1, intentHotspot(ctx),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, "Attiva hotspot", pi).build()
    }

    fun servizio(ctx: Context, testo: String): Notification =
        Notification.Builder(ctx, CANALE_SERVIZIO)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Sempre connessi")
            .setContentText(testo)
            .setContentIntent(apriApp(ctx))
            .setOngoing(true)
            .setShowWhen(false)
            .build()

    fun linea(ctx: Context, titolo: String, testo: String, allarme: Boolean, conHotspot: Boolean) {
        val b = Notification.Builder(ctx, if (allarme) CANALE_ALLARMI else CANALE_INFO)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(titolo)
            .setContentText(testo)
            .setStyle(Notification.BigTextStyle().bigText(testo))
            .setContentIntent(apriApp(ctx))
            .setAutoCancel(true)
            .setCategory(if (allarme) Notification.CATEGORY_ALARM else Notification.CATEGORY_STATUS)
        if (allarme) b.setColor(Color.RED)
        if (conHotspot) b.addAction(azioneHotspot(ctx))
        ctx.getSystemService(NotificationManager::class.java)!!.notify(ID_LINEA, b.build())
    }

    fun server(ctx: Context, titolo: String, testo: String) {
        val n = Notification.Builder(ctx, CANALE_INFO)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(titolo)
            .setContentText(testo)
            .setContentIntent(apriApp(ctx))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java)!!.notify(ID_SERVER, n)
    }

    fun togliServer(ctx: Context) =
        ctx.getSystemService(NotificationManager::class.java)!!.cancel(ID_SERVER)
}
