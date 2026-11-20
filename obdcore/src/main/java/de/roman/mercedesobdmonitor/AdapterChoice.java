package de.roman.mercedesobdmonitor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.IOException;
import java.util.List;

/**
 * Wahl des ELM327-Adapters für beide Apps (ADR-005): WLAN (Adresse/Port wie bisher) oder ein gekoppeltes
 * Bluetooth-Gerät. Gespeichert in den App-Einstellungen; „Verbinden“ nimmt den gewählten Weg.
 */
public final class AdapterChoice {
    public static final String KEY_BT_ADDRESS = "adapter_bt_address";
    public static final String KEY_BT_NAME = "adapter_bt_name";
    /** Anfragecode für BLUETOOTH_CONNECT; die Activity leitet onRequestPermissionsResult hierher. */
    public static final int REQ_BT = 43;

    private AdapterChoice() { }

    private static SharedPreferences prefs(Context c, String name) {
        return c.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    public static boolean usesBluetooth(Context c, String prefsName) {
        return !prefs(c, prefsName).getString(KEY_BT_ADDRESS, "").isEmpty();
    }

    /** Anzeige, z. B. „Adapter: WLAN 192.168.0.10:35000“ oder „Adapter: Bluetooth OBDII (00:1D:A5:…)“. */
    public static String label(Context c, String prefsName, String host, String port) {
        SharedPreferences p = prefs(c, prefsName);
        String addr = p.getString(KEY_BT_ADDRESS, "");
        if (addr.isEmpty()) return "Adapter: WLAN " + host + ":" + port;
        return "Adapter: Bluetooth " + p.getString(KEY_BT_NAME, "") + " (" + addr + ")";
    }

    /** Fehlt BLUETOOTH_CONNECT (Android 12+), wird gefragt; true = vorhanden. */
    public static boolean ensurePermission(Activity a) {
        if (BluetoothLink.hasPermission(a)) return true;
        if (Build.VERSION.SDK_INT >= 31) a.requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        return false;
    }

    /**
     * Auswahldialog: WLAN oder ein gekoppeltes Bluetooth-Gerät. Ohne Berechtigung wird sie erst angefragt (danach
     * erneut wählen).
     * @param theme Dialog-Theme oder 0
     */
    public static void pick(Activity a, String prefsName, int theme, Runnable changed) {
        List<BluetoothLink.Paired> paired;
        String hint = null;
        if (!BluetoothLink.hasPermission(a)) {
            // Ohne Berechtigung trotzdem zurück zu WLAN können (Review)
            (theme == 0 ? new AlertDialog.Builder(a) : new AlertDialog.Builder(a, theme))
                    .setTitle("ELM327-Adapter wählen")
                    .setMessage("Für einen Bluetooth-Adapter braucht die App die Berechtigung „Geräte in der Nähe“. "
                            + "Nach dem Erteilen erneut wählen.")
                    .setPositiveButton("Berechtigung erteilen", (d, w) -> ensurePermission(a))
                    .setNeutralButton("WLAN verwenden", (d, w) -> {
                        prefs(a, prefsName).edit().remove(KEY_BT_ADDRESS).remove(KEY_BT_NAME).apply();
                        changed.run();
                    })
                    .setNegativeButton("Abbrechen", null)
                    .show();
            return;
        }
        try {
            paired = BluetoothLink.pairedDevices(a);
        } catch (IOException e) {
            paired = java.util.Collections.emptyList();
            hint = e.getMessage();
        }
        String[] items = new String[paired.size() + 1];
        items[0] = "WLAN-Adapter (Adresse und Port wie eingetragen)";
        for (int i = 0; i < paired.size(); i++) {
            items[i + 1] = "Bluetooth: " + paired.get(i).name + " · " + paired.get(i).address;
        }
        final List<BluetoothLink.Paired> list = paired;
        AlertDialog.Builder b = theme == 0 ? new AlertDialog.Builder(a) : new AlertDialog.Builder(a, theme);
        b.setTitle("ELM327-Adapter wählen")
                .setItems(items, (d, which) -> {
                    SharedPreferences.Editor e = prefs(a, prefsName).edit();
                    if (which == 0) {
                        e.remove(KEY_BT_ADDRESS).remove(KEY_BT_NAME);
                    } else {
                        e.putString(KEY_BT_ADDRESS, list.get(which - 1).address)
                                .putString(KEY_BT_NAME, list.get(which - 1).name);
                    }
                    e.apply();
                    changed.run();
                })
                .setNegativeButton("Abbrechen", null);
        if (list.isEmpty()) {
            b.setMessage((hint != null ? hint + ". " : "") + "Kein gekoppeltes Bluetooth-Gerät: den Adapter zuerst in den "
                    + "Android-Einstellungen koppeln (PIN meist 1234 oder 0000).");
            b.setPositiveButton("WLAN verwenden", (d, w) -> {
                prefs(a, prefsName).edit().remove(KEY_BT_ADDRESS).remove(KEY_BT_NAME).apply();
                changed.run();
            });
        }
        b.show();
    }

    /** {@code changed} nach jeder Änderung von Adresse oder Port (damit die Adapteranzeige stimmt). */
    public static void watch(android.widget.EditText host, android.widget.EditText port, Runnable changed) {
        android.text.TextWatcher w = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable s) { changed.run(); }
        };
        host.addTextChangedListener(w);
        port.addTextChangedListener(w);
    }

    /** Verbindet über den gewählten Weg. */
    public static boolean connect(Activity a, ObdSession session, String prefsName, String host, int port) {
        SharedPreferences p = prefs(a, prefsName);
        String addr = p.getString(KEY_BT_ADDRESS, "");
        if (addr.isEmpty()) return session.connect(a, host, port);
        return session.connectBluetooth(a, addr, p.getString(KEY_BT_NAME, ""));
    }
}
