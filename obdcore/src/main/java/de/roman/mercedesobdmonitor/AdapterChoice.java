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
    /** Gemeinsame Schlüssel und Vorgaben beider Apps für Adresse und Port (Review: vorher dreifach kopiert). */
    public static final String KEY_HOST = "host";
    public static final String KEY_PORT = "port";
    public static final String DEFAULT_HOST = "192.168.0.10";
    public static final String DEFAULT_PORT = "35000";
    public static final String KEY_BT_ADDRESS = "adapter_bt_address";
    public static final String KEY_BT_NAME = "adapter_bt_name";
    /** Anfragecode für BLUETOOTH_CONNECT aus {@link #pick}; die Activity leitet ihn an {@link #onPermissionResult}. */
    public static final int REQ_BT = 43;
    /** Android 17+: Laufzeitberechtigung fürs lokale Netz (WLAN-Adapter). */
    public static final String LOCAL_NET_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";

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

    /** Fehlende Laufzeitberechtigungen für den gewählten Weg: Bluetooth oder WLAN (lokales Netz). */
    public static java.util.List<String> missingPermissions(Context c, String prefsName) {
        java.util.List<String> needed = new java.util.ArrayList<>();
        if (usesBluetooth(c, prefsName)) {
            if (!BluetoothLink.hasPermission(c)) needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
            if (Build.VERSION.SDK_INT >= 37 && c.checkSelfPermission(LOCAL_NET_PERMISSION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                needed.add(LOCAL_NET_PERMISSION);
            }
        }
        return needed;
    }

    /** Text, wenn die nötige Berechtigung abgelehnt wurde. */
    public static String deniedText(Context c, String prefsName) {
        return usesBluetooth(c, prefsName)
                ? "Ohne Bluetooth-Berechtigung ist keine Verbindung zum ELM327 möglich"
                : "Ohne Berechtigung für das lokale Netzwerk ist keine Verbindung zum ELM327 möglich";
    }

    /** Port aus dem Eingabefeld; bei Bluetooth egal (35000), bei WLAN null, wenn ungültig. */
    public static Integer port(Context c, String prefsName, String text) {
        try {
            int p = Integer.parseInt(text.trim());
            if (p >= 1 && p <= 65535) return p;
        } catch (NumberFormatException ignored) {
            // unten
        }
        return usesBluetooth(c, prefsName) ? 35000 : null;
    }

    /**
     * Ergebnis der Anfrage aus {@link #pick}: erteilt → Auswahl erneut öffnen; dauerhaft abgelehnt → Weg in die
     * Systemeinstellungen oder zurück zu WLAN (Review: das Ergebnis wurde vorher ignoriert).
     */
    public static void onPermissionResult(Activity a, String prefsName, int theme, int[] results, Runnable changed) {
        if (results.length == 0) return;   // abgebrochen
        if (results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pick(a, prefsName, theme, changed);
            return;
        }
        boolean permanently = Build.VERSION.SDK_INT >= 31
                && !a.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT);
        (theme == 0 ? new AlertDialog.Builder(a) : new AlertDialog.Builder(a, theme))
                .setTitle("Bluetooth-Berechtigung fehlt")
                .setMessage(permanently
                        ? "Die Berechtigung „Geräte in der Nähe“ ist dauerhaft abgelehnt. In den App-Einstellungen unter "
                        + "Berechtigungen erlauben oder den WLAN-Adapter verwenden."
                        : "Ohne die Berechtigung „Geräte in der Nähe“ kann die App keinen Bluetooth-Adapter verwenden.")
                .setPositiveButton(permanently ? "Einstellungen öffnen" : "Erneut fragen", (d, w) -> {
                    if (permanently) {
                        a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", a.getPackageName(), null)));
                    } else {
                        ensurePermission(a);
                    }
                })
                .setNeutralButton("WLAN verwenden", (d, w) -> {
                    prefs(a, prefsName).edit().remove(KEY_BT_ADDRESS).remove(KEY_BT_NAME).apply();
                    changed.run();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
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
        boolean bluetoothOff = false;
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
            bluetoothOff = hint != null && hint.contains("ausgeschaltet");
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
            // Bluetooth aus ist etwas anderes als „nicht gekoppelt“ (Review)
            b.setMessage(bluetoothOff
                    ? "Bluetooth ist ausgeschaltet. Einschalten und erneut wählen."
                    : (hint != null ? hint + ". " : "") + "Kein gekoppeltes Bluetooth-Gerät: den Adapter zuerst in den "
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
    public static boolean connect(Context c, ObdSession session, String prefsName, String host, int port) {
        SharedPreferences p = prefs(c, prefsName);
        String addr = p.getString(KEY_BT_ADDRESS, "");
        if (addr.isEmpty()) return session.connect(c, host, port);
        return session.connectBluetooth(c, addr, p.getString(KEY_BT_NAME, ""));
    }

    // ------------------------------------------------------------------ ADR-006: automatisch verbinden

    /** Einstellung „Automatisch verbinden“ (App-Start und Radiostart), ausgeliefert aus. */
    public static final String KEY_AUTO_CONNECT = "auto_connect";

    public static boolean autoConnect(Context c, String prefsName) {
        return prefs(c, prefsName).getBoolean(KEY_AUTO_CONNECT, false);
    }

    public static void setAutoConnect(Context c, String prefsName, boolean on) {
        prefs(c, prefsName).edit().putBoolean(KEY_AUTO_CONNECT, on).apply();
    }

    /** Ergebnis von {@link #connectFromSettings}. */
    public enum AutoResult {
        /** Verbindungsaufbau gestartet. */
        STARTED,
        /** Gerade nicht möglich (Bluetooth fährt hoch/aus, Diagnose läuft) – später erneut versuchen. */
        RETRY,
        /** Berechtigung fehlt oder Einstellungen ungültig – weitere Versuche helfen nicht. */
        GIVE_UP
    }

    /**
     * Verbindet mit den gespeicherten Einstellungen – ohne Activity, z. B. aus dem Dienst nach dem Radiostart.
     * Unterscheidet vorübergehende von dauerhaften Hindernissen (Review: Bluetooth beim Booten noch aus ist der
     * erwartete Fall, kein Grund aufzugeben).
     */
    public static AutoResult connectFromSettings(Context c, ObdSession session, String prefsName) {
        if (!missingPermissions(c, prefsName).isEmpty()) return AutoResult.GIVE_UP;
        SharedPreferences p = prefs(c, prefsName);
        String host = p.getString(KEY_HOST, DEFAULT_HOST);
        Integer port = port(c, prefsName, p.getString(KEY_PORT, DEFAULT_PORT));
        if (port == null || (!usesBluetooth(c, prefsName) && host.isEmpty())) return AutoResult.GIVE_UP;
        return connect(c, session, prefsName, host, port) ? AutoResult.STARTED : AutoResult.RETRY;
    }
}
