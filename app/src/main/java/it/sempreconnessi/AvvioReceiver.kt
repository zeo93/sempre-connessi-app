package it.sempreconnessi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Riavvia l'ascolto all'accensione del telefono, dopo un aggiornamento e con la sveglia periodica. */
class AvvioReceiver : BroadcastReceiver() {
    companion object {
        const val SVEGLIA = "it.sempreconnessi.SVEGLIA"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, SVEGLIA ->
                MonitorService.avvia(ctx)
        }
    }
}
