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
    private static final int REQ_TRIM_LOG = 42;
    private static final String PREFS = "elm";
    private static final String PREF_NOTIFICATIONS_ASKED = "notificationsAsked";
    private static final String PREF_ADR004 = "adr004_enabled";

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
    private Button profileButton;
    private Button atfButton;
    private Button starterButton;
    private Button canMonitorButton;
    private Button kwpButton;
    private android.widget.CheckBox adr004Box;
    private SparklineView latencyGraph;
    private TextView analysisText;
    private TextView tripsText;
    private TextView planText;
    private Button adapterButton;

    /**
     * Gemeinsame Auswertung mit dem Dashboard (Verbrauch, Lambda, Trim-Karte und -Verlauf, Kat-Tendenz, E-Gas,
     * Bordnetz); Verlauf in den Einstellungen dieser App.
     */
    private AnalysisController controller;

    private final Map<Integer, TextView> pidRows = new LinkedHashMap<>();

    private final ObdSession session = ObdSession.get();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        session.attachTestPlan(this);
        controller = new AnalysisController(this, PREFS, 1000);
        buildUi();
        loadSettings();
    }

    @Override
    protected void onStart() {
        super.onStart();
        session.setUiListener(this);
        controller.setListener(this::updateAnalysis);
        updateAnalysis();
        refreshTrips();
        refreshPlan();
    }

    @Override
    public void onResult(ObdSession.Op op, String title, String message) {
        onDialog(title, message);
        refreshPlan();
    }

    private void updateAdapterLabel() {
        if (adapterButton == null) return;
        adapterButton.setText(AdapterChoice.label(this, PREFS, hostInput.getText().toString().trim(),
                portInput.getText().toString().trim()) + " · ändern");
    }

    private void refreshPlan() {
        if (planText != null) planText.setText(session.testPlanChecklist());
    }

    private void confirmClearPlan() {
        PlanUi.confirmClear(this, 0, session, this::refreshPlan);
    }

    private String appVersion() {
        return PlanUi.appVersion(this);
    }

    @Override
    protected void onStop() {
        session.clearUiListener(this);
        // Ohne Anmeldung kommen keine Werte: nichts Veraltetes auswerten, laufende Trim-Karte sichern
        controller.onStop();
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

        TextView sub = text("C350 S204 / M272 · ELM327 WiFi", 14, Color.LTGRAY);
        root.addView(sub);

        TextView safety = text("🔒 READ-ONLY · Keine Codierung · Kein Löschen · Keine Stellgliedbefehle", 13,
                Color.rgb(129, 199, 132));
        safety.setPadding(0, dp(6), 0, dp(4));
        root.addView(safety);

        LinearLayout endpoint = row();
        hostInput = edit(AdapterChoice.DEFAULT_HOST, false);
        portInput = edit(AdapterChoice.DEFAULT_PORT, true);
        endpoint.addView(hostInput, new LinearLayout.LayoutParams(0, dp(48), 2f));
        endpoint.addView(portInput, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(endpoint);
        adapterButton = button("");
        adapterButton.setAllCaps(false);
        adapterButton.setOnClickListener(v -> AdapterChoice.pick(this, PREFS, 0, this::updateAdapterLabel));
        root.addView(adapterButton);
        AdapterChoice.watch(hostInput, portInput, this::updateAdapterLabel);

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

        LinearLayout c350Controls = row();
        profileButton = button("Fahrzeugprofil");
        atfButton = button("ATF-Temperatur");
        c350Controls.addView(profileButton, weight());
        c350Controls.addView(atfButton, weight());
        root.addView(c350Controls);
        LinearLayout c350Controls2 = row();
        starterButton = button("Starttest");
        c350Controls2.addView(starterButton, weight());
        canMonitorButton = button("Bus mitlesen");
        c350Controls2.addView(canMonitorButton, weight());
        root.addView(c350Controls2);
        // ADR-004: Testfreigabe für zwei Herstellerabfragen, ausgeliefert aus; erst angehakt erscheint der Knopf
        adr004Box = new android.widget.CheckBox(this);
        adr004Box.setText("Herstellerabfragen nach ADR-004 (Test): Kennung 1A 86, Getriebe-Fehler 18 02 FF 00");
        adr004Box.setTextColor(Color.LTGRAY);
        adr004Box.setTextSize(13);
        root.addView(adr004Box);
        kwpButton = button("Kennung + Getriebe-Fehler (Test)");
        root.addView(kwpButton);

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

        TextView analysisTitle = text("Analyse (C350/M272)", 18, Color.WHITE);
        analysisTitle.setTypeface(null, Typeface.BOLD);
        analysisTitle.setPadding(0, dp(14), 0, dp(2));
        root.addView(analysisTitle);
        TextView analysisNote = text("Aus Standardwerten berechnet · Schwellen sind Richtwerte, keine Herstellerangaben", 13,
                Color.rgb(255, 204, 118));
        analysisNote.setPadding(0, 0, 0, dp(6));
        root.addView(analysisNote);
        analysisText = text("Werte erscheinen, sobald verbunden.", 13, Color.rgb(210, 214, 220));
        analysisText.setTextIsSelectable(true);
        analysisText.setPadding(dp(8), dp(8), dp(8), dp(8));
        analysisText.setBackgroundColor(Color.rgb(29, 33, 40));
        root.addView(analysisText);
        Button importLog = button("Log importieren (Trim-Verlauf)");
        root.addView(importLog, new LinearLayout.LayoutParams(-1, dp(48)));

        TextView tripsTitle = text("Fahrtenbuch", 18, Color.WHITE);
        tripsTitle.setTypeface(null, Typeface.BOLD);
        tripsTitle.setPadding(0, dp(14), 0, dp(2));
        root.addView(tripsTitle);
        TextView tripsNote = text("Aus den CSV-Logs dieser App und importierten Logs · Verbrauch aus Luftmasse und Soll-λ "
                + "(Richtwert, kein Tankbeleg)", 13, Color.rgb(255, 204, 118));
        tripsNote.setPadding(0, 0, 0, dp(6));
        root.addView(tripsNote);
        tripsText = text(controller.tripReport(AnalysisController.TRIP_ROWS), 13, Color.rgb(210, 214, 220));
        tripsText.setTextIsSelectable(true);
        tripsText.setPadding(dp(8), dp(8), dp(8), dp(8));
        tripsText.setBackgroundColor(Color.rgb(29, 33, 40));
        root.addView(tripsText);
        LinearLayout tripControls = row();
        Button refreshTrips = button("Fahrten aktualisieren");
        Button shareTrips = button("Fahrten teilen (CSV)");
        tripControls.addView(refreshTrips, weight());
        tripControls.addView(shareTrips, weight());
        root.addView(tripControls);

        TextView planTitle = text("Prüfplan – nächster Termin am Auto", 18, Color.WHITE);
        planTitle.setTypeface(null, Typeface.BOLD);
        planTitle.setPadding(0, dp(14), 0, dp(2));
        root.addView(planTitle);
        TextView planNote = text("Hakt die offenen Fahrzeugtests anhand der Ergebnisse ab und sammelt alle Rohantworten "
                + "in einem Bericht zum Teilen.", 12, Color.LTGRAY);
        planNote.setPadding(0, 0, 0, dp(6));
        root.addView(planNote);
        planText = text("", 13, Color.rgb(210, 214, 220));
        planText.setTextIsSelectable(true);
        planText.setPadding(dp(8), dp(8), dp(8), dp(8));
        planText.setBackgroundColor(Color.rgb(29, 33, 40));
        root.addView(planText);
        LinearLayout planControls = row();
        Button sharePlan = button("Prüfplan teilen");
        Button clearPlan = button("Prüfplan leeren");
        planControls.addView(sharePlan, weight());
        planControls.addView(clearPlan, weight());
        root.addView(planControls);
        sharePlan.setOnClickListener(v -> startActivity(session.testPlanShareIntent("Monitor " + appVersion())));
        clearPlan.setOnClickListener(v -> confirmClearPlan());

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
        disconnect.setOnClickListener(v -> {
            session.disconnect();
            controller.connectionEnded();
        });
        dtcButton.setOnClickListener(v -> session.readDtcs());
        fuelTestButton.setOnClickListener(v -> session.toggleFuelTrimTest());
        misfireButton.setOnClickListener(v -> session.runMisfireAnalysis());
        inspectionButton.setOnClickListener(v -> session.runInspectionCheck());
        monitorTestsButton.setOnClickListener(v -> session.runMonitorTests());
        profileButton.setOnClickListener(v -> session.runVehicleProfile());
        atfButton.setOnClickListener(v -> session.runAtfTemperature());
        starterButton.setOnClickListener(v -> session.toggleStarterTest());
        canMonitorButton.setOnClickListener(v -> session.runCanMonitor());
        kwpButton.setOnClickListener(v -> session.runKwpIdentDtc());
        adr004Box.setOnCheckedChangeListener((v, checked) -> {
            session.setAdr004Enabled(checked);
            kwpButton.setVisibility(checked ? View.VISIBLE : View.GONE);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ADR004, checked).apply();
        });
        warmupButton.setOnClickListener(v -> session.toggleWarmupCheck());
        send.setOnClickListener(v -> session.sendTerminal(terminalInput.getText().toString()));
        export.setOnClickListener(v -> session.exportCsv(this));
        newLog.setOnClickListener(v -> session.startNewLog());
        importLog.setOnClickListener(v -> pickTrimLog());
        refreshTrips.setOnClickListener(v -> refreshTrips());
        shareTrips.setOnClickListener(v -> controller.shareTrips(this));

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
        Integer port = AdapterChoice.port(this, PREFS, portInput.getText().toString());
        if (port == null) {
            status.setText("Ungültiger TCP-Port");
            return;
        }

        saveSettings();
        if (!AdapterChoice.connect(this, session, PREFS, host, port)) return;
        // Neue Verbindung, evtl. anderes Fahrzeug: die alte behält ihren Verlaufseintrag, Karte und Kat-Tendenz neu
        controller.newConnection();
        // Ab hier läuft die Verbindung im Foreground-Service weiter, auch ohne sichtbare Activity.
        startForegroundService(new Intent(this, ObdService.class).setAction(ObdService.ACTION_START));
    }

    private boolean ensurePermissions() {
        // je nach Adapter Bluetooth- oder WLAN-Berechtigungen (ADR-005)
        List<String> needed = AdapterChoice.missingPermissions(this, PREFS);
        // Benachrichtigung ist optional (Dienst läuft auch ohne); nur einmal nachfragen.
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
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
        if (requestCode == AdapterChoice.REQ_BT) {
            AdapterChoice.onPermissionResult(this, PREFS, 0, results, this::updateAdapterLabel);
            return;
        }
        if (requestCode != REQ_NET) return;
        // Leere Ergebnisse = Anfrage abgebrochen; nicht erneut anfragen (Endlosschleife).
        boolean ok = results.length > 0;
        for (int i = 0; i < results.length && i < permissions.length; i++) {
            if (Manifest.permission.POST_NOTIFICATIONS.equals(permissions[i])) continue;
            ok &= results[i] == PackageManager.PERMISSION_GRANTED;
        }
        if (ok) connectRequested();
        else Toast.makeText(this, AdapterChoice.deniedText(this, PREFS), Toast.LENGTH_LONG).show();
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
    public void onPidSample(int pid, double value) {
        controller.onSample(pid, value);
    }

    @Override
    public void onPidInvalid(int pid) {
        controller.onInvalid(pid);
    }

    private void updateAnalysis() {
        controller.showIn(analysisText);
    }

    /** Logs im Hintergrund nachlesen; solange bleibt der gespeicherte Stand sichtbar. */
    private void refreshTrips() {
        controller.refreshTripsInto(tripsText, this);
    }

    private void pickTrimLog() {
        controller.pickLog(this, REQ_TRIM_LOG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        controller.onActivityResult(requestCode, REQ_TRIM_LOG, resultCode, data,
                () -> controller.showTripsIn(tripsText));
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
            case PROFILE -> { b = profileButton; idle = "Fahrzeugprofil"; working = "Lese Profil …"; }
            case ATF -> { b = atfButton; idle = "ATF-Temperatur"; working = "Lese Getriebe …"; }
            case STARTER -> {
                // bleibt bedienbar: zweiter Druck bricht ab
                starterButton.setText(busy ? "Starttest abbrechen" : "Starttest");
                return;
            }
            case CAN_MONITOR -> { b = canMonitorButton; idle = "Bus mitlesen"; working = "Lese Bus (5 s) …"; }
            case KWP -> { b = kwpButton; idle = "Kennung + Getriebe-Fehler (Test)"; working = "Lese Steuergeräte …"; }
            case INSPECTION -> { b = inspectionButton; idle = "HU/AU-Check"; working = "Lese Readiness …"; }
            default -> { return; }
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
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(AdapterChoice.KEY_HOST, hostInput.getText().toString().trim())
                .putString(AdapterChoice.KEY_PORT, portInput.getText().toString().trim())
                .apply();
    }

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        hostInput.setText(p.getString(AdapterChoice.KEY_HOST, AdapterChoice.DEFAULT_HOST));
        portInput.setText(p.getString(AdapterChoice.KEY_PORT, AdapterChoice.DEFAULT_PORT));
        updateAdapterLabel();
        boolean adr004 = p.getBoolean(PREF_ADR004, false);
        adr004Box.setChecked(adr004);
        session.setAdr004Enabled(adr004);
        kwpButton.setVisibility(adr004 ? View.VISIBLE : View.GONE);
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
