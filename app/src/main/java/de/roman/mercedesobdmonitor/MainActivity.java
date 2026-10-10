package de.roman.mercedesobdmonitor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reine Oberfläche. Verbindung, Polling und Diagnosen laufen in der prozessweiten
 * {@link ObdSession}, im Hintergrund vom {@link ObdService} gehalten. Die Activity
 * meldet sich in onStart an und zeichnet sich aus dem Sitzungszustand neu – auch
 * nach Bildschirm aus, App-Wechsel oder Neuerzeugung.
 */
public final class MainActivity extends Activity implements ObdSession.Listener {
    private static final int REQ_NET = 41;
    private static final String LOCAL_NET_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    private static final String PREF_NOTIFICATIONS_ASKED = "notificationsAsked";

    private EditText hostInput;
    private EditText portInput;
    private EditText terminalInput;
    private TextView status;
    private TextView stats;
    private TextView operatingState;
    private TextView voltageState;
    private TextView fuelTestStatus;
    private TextView warmupStatus;
    private TextView driveCycleState;
    private TextView console;
    private LinearLayout pidBox;
    private Button dtcButton;
    private Button fuelTestButton;
    private Button misfireButton;
    private Button inspectionButton;
    private Button monitorTestsButton;
    private Button warmupButton;
    private SparklineView latencyGraph;

    private final Map<Integer, TextView> pidRows = new LinkedHashMap<>();

    private final ObdSession session = ObdSession.get();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        loadSettings();
    }

    @Override
    protected void onStart() {
        super.onStart();
        session.setUiListener(this);
    }

    @Override
    protected void onStop() {
        session.clearUiListener(this);
        super.onStop();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        // targetSdk 35+ erzwingt Edge-to-Edge: Inhalt nicht unter Status-/Navigationsleiste legen.
        scroll.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(28));
        root.setBackgroundColor(Color.rgb(17, 19, 24));
        scroll.addView(root);

        TextView title = text("Mercedes OBD2 Monitor", 24, Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView sub = text("C350 W204 / M272 · ELM327 WiFi", 14, Color.LTGRAY);
        root.addView(sub);

        TextView safety = text("🔒 READ-ONLY · Keine Codierung · Kein Löschen · Keine Stellgliedbefehle", 13,
                Color.rgb(129, 199, 132));
        safety.setPadding(0, dp(6), 0, dp(4));
        root.addView(safety);

        LinearLayout endpoint = row();
        hostInput = edit("192.168.0.10", false);
        portInput = edit("35000", true);
        endpoint.addView(hostInput, new LinearLayout.LayoutParams(0, dp(48), 2f));
        endpoint.addView(portInput, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(endpoint);

        LinearLayout controls = row();
        Button connect = button("Verbinden");
        Button disconnect = button("Trennen");
        dtcButton = button("DTC");
        controls.addView(connect, weight());
        controls.addView(disconnect, weight());
        controls.addView(dtcButton, weight());
        root.addView(controls);

        status = text("Nicht verbunden", 15, Color.WHITE);
        status.setPadding(0, dp(8), 0, dp(4));
        root.addView(status);

        stats = text("", 13, Color.LTGRAY);
        root.addView(stats);

        operatingState = text("Betriebszustand: –", 14, Color.LTGRAY);
        operatingState.setPadding(0, dp(7), 0, dp(2));
        root.addView(operatingState);

        voltageState = text("Spannungsbewertung: –", 13, Color.LTGRAY);
        root.addView(voltageState);

        driveCycleState = text("", 13, Color.LTGRAY);
        driveCycleState.setVisibility(View.GONE);
        root.addView(driveCycleState);

        fuelTestStatus = text("Fuel-Trim-Test: bereit", 13, Color.LTGRAY);
        fuelTestStatus.setPadding(0, dp(3), 0, dp(4));
        root.addView(fuelTestStatus);

        fuelTestButton = button("Fuel-Trim-Test");
        root.addView(fuelTestButton, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout diagControls = row();
        misfireButton = button("Aussetzer (Mode 06)");
        inspectionButton = button("HU/AU-Check");
        diagControls.addView(misfireButton, weight());
        diagControls.addView(inspectionButton, weight());
        root.addView(diagControls);

        LinearLayout moreControls = row();
        monitorTestsButton = button("Monitortests (Mode 06)");
        warmupButton = button("Warmlauf-Check");
        moreControls.addView(monitorTestsButton, weight());
        moreControls.addView(warmupButton, weight());
        root.addView(moreControls);

        warmupStatus = text("Warmlauf-Check: bereit", 13, Color.LTGRAY);
        warmupStatus.setPadding(0, dp(3), 0, dp(4));
        root.addView(warmupStatus);

        latencyGraph = new SparklineView(this);
        root.addView(latencyGraph, new LinearLayout.LayoutParams(-1, dp(135)));

        TextView liveTitle = text("Livewerte", 18, Color.WHITE);
        liveTitle.setTypeface(null, Typeface.BOLD);
        liveTitle.setPadding(0, dp(12), 0, dp(6));
        root.addView(liveTitle);

        pidBox = new LinearLayout(this);
        pidBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(pidBox);
        createPidRows();

        LinearLayout logControls = row();
        Button export = button("CSV exportieren");
        Button newLog = button("Neues Log");
        logControls.addView(export, weight());
        logControls.addView(newLog, weight());
        root.addView(logControls);

        TextView termTitle = text("ELM327-Terminal (Read-Only)", 18, Color.WHITE);
        termTitle.setTypeface(null, Typeface.BOLD);
        termTitle.setPadding(0, dp(14), 0, dp(6));
        root.addView(termTitle);

        LinearLayout terminal = row();
        terminalInput = edit("ATI", false);
        Button send = button("Senden");
        terminal.addView(terminalInput, new LinearLayout.LayoutParams(0, dp(48), 3f));
        terminal.addView(send, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(terminal);

        console = text("Bereit.\n", 12, Color.rgb(210, 214, 220));
        console.setTextIsSelectable(true);
        console.setPadding(dp(8), dp(8), dp(8), dp(8));
        console.setBackgroundColor(Color.rgb(29, 33, 40));
        root.addView(console, new LinearLayout.LayoutParams(-1, dp(230)));

        connect.setOnClickListener(v -> connectRequested());
        disconnect.setOnClickListener(v -> session.disconnect());
        dtcButton.setOnClickListener(v -> session.readDtcs());
        fuelTestButton.setOnClickListener(v -> session.toggleFuelTrimTest());
        misfireButton.setOnClickListener(v -> session.runMisfireAnalysis());
        inspectionButton.setOnClickListener(v -> session.runInspectionCheck());
        monitorTestsButton.setOnClickListener(v -> session.runMonitorTests());
        warmupButton.setOnClickListener(v -> session.toggleWarmupCheck());
        send.setOnClickListener(v -> session.sendTerminal(terminalInput.getText().toString()));
        export.setOnClickListener(v -> session.exportCsv(this));
        newLog.setOnClickListener(v -> session.startNewLog());

        setContentView(scroll);
    }

    private void createPidRows() {
        pidBox.removeAllViews();
        pidRows.clear();
        for (ObdPid pid : ObdPid.defaultPids()) {
            LinearLayout r = row();
            TextView name = text(pid.label, 14, Color.LTGRAY);
            TextView value = text("–", 15, Color.WHITE);
            value.setGravity(Gravity.END);
            r.addView(name, new LinearLayout.LayoutParams(0, dp(34), 2f));
            r.addView(value, new LinearLayout.LayoutParams(0, dp(34), 1f));
            pidBox.addView(r);
            pidRows.put(pid.pid, value);
        }
    }

    private void connectRequested() {
        if (!ensurePermissions()) return;

        final String host = hostInput.getText().toString().trim();
        final int port;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            status.setText("Ungültiger TCP-Port");
            return;
        }

        saveSettings();
        if (!session.connect(this, host, port)) return;
        // Ab hier läuft die Verbindung im Foreground-Service weiter, auch ohne sichtbare Activity.
        startForegroundService(new Intent(this, ObdService.class).setAction(ObdService.ACTION_START));
    }

    private boolean ensurePermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        if (Build.VERSION.SDK_INT >= 37
                && checkSelfPermission(LOCAL_NET_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(LOCAL_NET_PERMISSION);
        }
        // Benachrichtigung ist optional (Dienst läuft auch ohne); nur einmal nachfragen.
        SharedPreferences prefs = getSharedPreferences("elm", MODE_PRIVATE);
        boolean askNotifications = Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean(PREF_NOTIFICATIONS_ASKED, false);
        if (needed.isEmpty() && !askNotifications) return true;
        if (askNotifications) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
            prefs.edit().putBoolean(PREF_NOTIFICATIONS_ASKED, true).apply();
        }
        requestPermissions(needed.toArray(new String[0]), REQ_NET);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_NET) return;
        // Leere Ergebnisse = Anfrage abgebrochen; nicht erneut anfragen (Endlosschleife).
        boolean ok = results.length > 0;
        for (int i = 0; i < results.length && i < permissions.length; i++) {
            if (Manifest.permission.POST_NOTIFICATIONS.equals(permissions[i])) continue;
            ok &= results[i] == PackageManager.PERMISSION_GRANTED;
        }
        if (ok) connectRequested();
        else Toast.makeText(this, "Netzwerkberechtigung wurde nicht erteilt", Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- ObdSession.Listener

    @Override
    public void render(ObdSession.State s) {
        status.setText(s.status);
        stats.setText(s.stats);
        console.setText(s.console());
        for (Map.Entry<Integer, TextView> row : pidRows.entrySet()) {
            String value = s.pidTexts.get(row.getKey());
            row.getValue().setText(value == null ? "–" : value);
        }
        latencyGraph.clear();
        for (long ms : s.latencies) latencyGraph.addValue(ms);
        onPassive(s);
        onFuelTest(s.fuelButton, s.fuelStatus);
        for (ObdSession.Op op : ObdSession.Op.values()) onBusy(op, s.busy.contains(op));
    }

    @Override
    public void onStatus(String text) {
        status.setText(text);
    }

    @Override
    public void onConsole(String text) {
        console.setText(text);
    }

    @Override
    public void onPidValue(int pid, String text) {
        TextView v = pidRows.get(pid);
        if (v != null) v.setText(text);
    }

    @Override
    public void onLatency(long ms) {
        latencyGraph.addValue(ms);
    }

    @Override
    public void onStats(String text) {
        stats.setText(text);
    }

    @Override
    public void onPassive(ObdSession.State s) {
        operatingState.setText(s.operatingText);
        operatingState.setTextColor(s.operatingColor);
        voltageState.setText(s.voltageText);
        warmupButton.setText(s.warmupButton);
        warmupStatus.setText(s.warmupStatus);
        driveCycleState.setText(s.driveCycleText == null ? "" : s.driveCycleText);
        driveCycleState.setVisibility(s.driveCycleText == null ? View.GONE : View.VISIBLE);
        int liveColor = s.koeo ? Color.GRAY : Color.WHITE;
        for (int pid : new int[] {0x06, 0x08, 0x44}) {
            TextView v = pidRows.get(pid);
            if (v != null) v.setTextColor(liveColor);
        }
    }

    @Override
    public void onFuelTest(String button, String text) {
        fuelTestButton.setText(button);
        fuelTestStatus.setText(text);
    }

    @Override
    public void onBusy(ObdSession.Op op, boolean busy) {
        Button b;
        String idle;
        String working;
        switch (op) {
            case DTC -> { b = dtcButton; idle = "DTC"; working = "Lese DTC…"; }
            case MISFIRE -> { b = misfireButton; idle = "Aussetzer (Mode 06)"; working = "Lese Mode 06 …"; }
            case MONITOR_TESTS -> { b = monitorTestsButton; idle = "Monitortests (Mode 06)"; working = "Lese Monitortests …"; }
            default -> { b = inspectionButton; idle = "HU/AU-Check"; working = "Lese Readiness …"; }
        }
        b.setEnabled(!busy);
        b.setText(busy ? working : idle);
    }

    /** Zeigt einen OK-Dialog; nach onDestroy (z. B. später fertiger Scan) wird nichts angezeigt. */
    @Override
    public void onDialog(String title, String message) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    @Override
    public void onToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- Einstellungen, Views

    private void saveSettings() {
        getSharedPreferences("elm", MODE_PRIVATE).edit()
                .putString("host", hostInput.getText().toString().trim())
                .putString("port", portInput.getText().toString().trim())
                .apply();
    }

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences("elm", MODE_PRIVATE);
        hostInput.setText(p.getString("host", "192.168.0.10"));
        portInput.setText(p.getString("port", "35000"));
    }

    private TextView text(String value, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private EditText edit(String hint, boolean numeric) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.GRAY);
        e.setSingleLine(true);
        e.setPadding(dp(8), 0, dp(8), 0);
        if (numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, dp(48), 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
