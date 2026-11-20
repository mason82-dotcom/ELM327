package de.roman.obddashboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import de.roman.mercedesobdmonitor.AdapterChoice;
import de.roman.mercedesobdmonitor.ObdService;

/**
 * ADR-006: Nach dem Start des Android-Radios automatisch mit dem ELM327 verbinden (nur wenn unter INFO
 * „Automatisch verbinden“ an ist). Startet nur den Vordergrunddienst; die Oberfläche darf Android 10+ nicht aus dem
 * Hintergrund öffnen – dafür die Autostart-Liste des Radios verwenden.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!AdapterChoice.autoConnect(context, DashboardActivity.PREFS)) return;
        context.startForegroundService(new Intent(context, ObdService.class)
                .setAction(ObdService.ACTION_AUTO_CONNECT)
                .putExtra(ObdService.EXTRA_PREFS, DashboardActivity.PREFS));
    }
}
