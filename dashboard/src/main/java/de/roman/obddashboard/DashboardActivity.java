package de.roman.obddashboard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import de.roman.mercedesobdmonitor.ObdPid;
import de.roman.mercedesobdmonitor.ObdService;
import de.roman.mercedesobdmonitor.ObdSession;
import de.roman.mercedesobdmonitor.VehicleProfile;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fahr-Dashboard nach dem Vorbild des OpenNextion-ELM327-Displays: Reiter LIVE / GRAPH / DTC / INFO,
 * Unterseiten für alle Werte, Mode 06, HU/AU-Vorcheck, Fuel-Trim-Test und DTC-Details.
 *
 * Nur Anzeige und Bedienung: Verbindung, Polling, Read-only-Allowlist und alle Diagnosen liegen im
 * geprüften Kern {@link ObdSession} (Modul :obdcore). Keine Codierung, kein Löschen, keine Stellglieder.
 */
public final class DashboardActivity extends Activity implements ObdSession.Listener {
    private static final String LOCAL_NET_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    private static final String PREFS = "dashboard";
    private static final String PREF_NOTIFICATIONS_ASKED = "notifications_asked";
    private static final int REQ_NET = 41;
    private static final int TREND_POINTS = 240;

    private enum Page { LIVE, GRAPH, DTC, INFO, VALUES, MODE06, INSPECTION, TRIM, DTC_DETAILS }

    /** Reiter, unter dem eine Unterseite hervorgehoben wird. */
    private static Page tabOf(Page p) {
        switch (p) {
            case VALUES: case MODE06: case TRIM: return Page.LIVE;
            case INSPECTION: case DTC_DETAILS: return Page.DTC;
            default: return p;
        }
    }

    private static final class TrendSpec {
        final int pid; final String label; final String unit; final double min; final double max;
        TrendSpec(int pid, String label, String unit, double min, double max) {
            this.pid = pid; this.label = label; this.unit = unit; this.min = min; this.max = max;
        }
    }

    private static final TrendSpec[] TRENDS = {
            // Same ranges as the gauges (GaugeScale is the single source).
            new TrendSpec(0x0C, "Drehzahl", "U/min", GaugeScale.RPM.min, GaugeScale.RPM.max),
            new TrendSpec(0x0D, "Tempo", "km/h", GaugeScale.SPEED.min, GaugeScale.SPEED.max),
            new TrendSpec(0x05, "Kühlmittel", "°C", GaugeScale.COOLANT.min, GaugeScale.COOLANT.max),
            new TrendSpec(0x04, "Last", "%", 0, 100),
            new TrendSpec(0x06, "STFT B1", "%", -25, 25),
            new TrendSpec(0x08, "STFT B2", "%", -25, 25),
            new TrendSpec(0x07, "LTFT B1", "%", -25, 25),
            new TrendSpec(0x09, "LTFT B2", "%", -25, 25),
    };

    private final ObdSession session = ObdSession.get();
    private final VehicleProfile profile = VehicleProfile.ACTIVE;
    private double lastRpm = Double.NaN, lastSpeed = Double.NaN;
    /** Verlauf je PID; überlebt den Neuaufbau der Oberfläche beim Drehen. */
    private final Map<Integer, TrendBuffer> trends = new HashMap<>();
    private final Map<ResultRouter.Target, String> results = new EnumMap<>(ResultRouter.Target.class);
    /** Short DTC summary of the last scan; survives rebuilding the UI on rotation. */
    private String dtcSummary;
    /** Last valid sample per PID while this UI is attached; restores the gauges after a rebuild. */
    private final Map<Integer, Double> lastSamples = new HashMap<>();
    private Page page = Page.LIVE;
    private int trendPid = 0x0C;

    // Views (werden bei jeder Konfigurationsänderung neu aufgebaut)
    private final Map<Page, View> pages = new EnumMap<>(Page.class);
    private final Map<Page, Button> tabs = new EnumMap<>(Page.class);
    private final Map<Integer, GaugeView> gauges = new HashMap<>();
    private final Map<Integer, TextView> tiles = new HashMap<>();
    private final Map<Integer, TextView> valueRows = new LinkedHashMap<>();
    private final Map<Integer, Button> trendButtons = new HashMap<>();
    private final Map<ObdSession.Op, List<Button>> opButtons = new EnumMap<>(ObdSession.Op.class);
    private final Map<ObdSession.Op, Boolean> busyOps = new EnumMap<>(ObdSession.Op.class);
    private TextView status, linkState, operating, idleState, busState, voltage, driveCycle, stats, console;
    private TextView connectHint, wifiHint;
    private TextView dtcState, dtcDetails, mode06Text, inspectionText, trimStatus, warmupStatus, trimReport;
    private Button connectButton, fuelButton, warmupButton;
    private TrendView trendView;
    private TextView trendTitle;
    private EditText hostInput, portInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        for (TrendSpec t : TRENDS) trends.put(t.pid, new TrendBuffer(TREND_POINTS));
        if (savedInstanceState != null) {
            try { page = Page.valueOf(savedInstanceState.getString("page", "LIVE")); } catch (IllegalArgumentException ignored) { }
            trendPid = savedInstanceState.getInt("trendPid", 0x0C);
        }
        buildUi();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("page", page.name());
        out.putInt("trendPid", trendPid);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        saveSettings();
        buildUi();
        session.setUiListener(this); // zeichnet den aktuellen Zustand neu
    }

    @Override
    protected void onStart() {
        super.onStart();
        session.setUiListener(this);
    }

    @Override
    protected void onStop() {
        session.clearUiListener(this);
        // While detached no samples arrive: the curves must not bridge that time (review of #4).
        for (TrendBuffer t : trends.values()) t.addGap();
        lastSamples.clear();
        saveSettings();
        super.onStop();
    }

    // ------------------------------------------------------------------ Aufbau

    private boolean landscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void buildUi() {
        pages.clear(); tabs.clear(); gauges.clear(); tiles.clear(); valueRows.clear(); trendButtons.clear(); opButtons.clear();
        String host = hostInput != null ? hostInput.getText().toString() : null;
        String port = portInput != null ? portInput.getText().toString() : null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Palette.BG);
        root.setFitsSystemWindows(true);

        // Kopfzeile: Titel, Status, Verbinden/Trennen
        LinearLayout head = row();
        head.setPadding(dp(14), dp(8), dp(10), dp(6));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(profile.shortName.toUpperCase(java.util.Locale.GERMANY) + "  /  READ-ONLY", 13, Palette.CYAN);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.08f);
        titles.addView(title);
        status = text("Nicht verbunden", 14, Palette.MUTED);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        titles.addView(status);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        connectButton = button("Verbinden", v -> toggleConnection());
        head.addView(connectButton, new LinearLayout.LayoutParams(dp(140), dp(52)));
        root.addView(head);

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        pages.put(Page.LIVE, buildLive());
        pages.put(Page.GRAPH, buildGraph());
        pages.put(Page.DTC, buildDtc());
        pages.put(Page.INFO, buildInfo(host, port));
        pages.put(Page.VALUES, buildValues());
        pages.put(Page.MODE06, buildMode06());
        pages.put(Page.INSPECTION, buildInspection());
        pages.put(Page.TRIM, buildTrim());
        pages.put(Page.DTC_DETAILS, buildDtcDetails());
        for (View p : pages.values()) content.addView(p, new FrameLayout.LayoutParams(-1, -1));

        // Untere Reiter wie am Display
        LinearLayout nav = row();
        nav.setPadding(dp(6), dp(4), dp(6), dp(6));
        for (Page p : new Page[]{Page.LIVE, Page.GRAPH, Page.DTC, Page.INFO}) {
            Button b = button(p.name(), v -> show(p));
            tabs.put(p, b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            nav.addView(b, lp);
        }
        root.addView(nav);
        setContentView(root);
        show(page);
        refreshResults();
    }

    private View buildLive() {
        LinearLayout page = column();
        page.setPadding(dp(12), dp(4), dp(12), dp(4));
        GaugeView rpm = new GaugeView(this, "Drehzahl", "U/min", 0, GaugeScale.RPM);
        GaugeView coolant = new GaugeView(this, "Kühlmittel", "°C", 0, GaugeScale.COOLANT);
        GaugeView speed = new GaugeView(this, "Tempo", "km/h", 0, GaugeScale.SPEED);
        gauges.put(0x0C, rpm); gauges.put(0x05, coolant); gauges.put(0x0D, speed);

        if (landscape()) {
            LinearLayout top = row();
            top.addView(rpm, new LinearLayout.LayoutParams(0, -1, 1.25f));
            top.addView(coolant, new LinearLayout.LayoutParams(0, -1, 1f));
            top.addView(speed, new LinearLayout.LayoutParams(0, -1, 1f));
            page.addView(top, new LinearLayout.LayoutParams(-1, 0, 1f));
        } else {
            page.addView(rpm, new LinearLayout.LayoutParams(-1, 0, 1.2f));
            LinearLayout pair = row();
            pair.addView(coolant, new LinearLayout.LayoutParams(0, -1, 1f));
            pair.addView(speed, new LinearLayout.LayoutParams(0, -1, 1f));
            page.addView(pair, new LinearLayout.LayoutParams(-1, 0, 0.9f));
        }

        // Kraftstoffkorrektur und Spannung als Kacheln (M272: Bank 1 = Zyl. 1–3, Bank 2 = Zyl. 4–6)
        int[][] tileDefs = {{0x06}, {0x08}, {0x07}, {0x09}, {0x42}};
        String[] tileNames = {"STFT B1", "STFT B2", "LTFT B1", "LTFT B2", "ECU-Spannung"};
        LinearLayout tileRow = row();
        LinearLayout tileRow2 = landscape() ? tileRow : row();
        for (int i = 0; i < tileDefs.length; i++) {
            LinearLayout card = card();
            card.addView(text(tileNames[i], 12, Palette.MUTED));
            TextView v = text(ObdSession.INVALID_TEXT, 20, Palette.LIGHT);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(v);
            tiles.put(tileDefs[i][0], v);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            (landscape() || i < 3 ? tileRow : tileRow2).addView(card, lp);
        }
        page.addView(tileRow);
        if (!landscape()) page.addView(tileRow2);

        LinearLayout foot = row();
        foot.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout states = column();
        operating = text("Betriebszustand: –", 15, Palette.MUTED);
        idleState = text("", 13, Palette.MUTED);
        states.addView(operating);
        states.addView(idleState);
        foot.addView(states, new LinearLayout.LayoutParams(0, -2, 1f));
        foot.addView(button("ALLE WERTE", v -> show(Page.VALUES)), new LinearLayout.LayoutParams(dp(150), dp(52)));
        page.addView(foot);
        TextView note = text("Keine Codierung · kein Löschen von Fehlercodes · keine Stellglieder", 12, Palette.TEAL);
        page.addView(note);
        return page;
    }

    private View buildGraph() {
        LinearLayout page = column();
        page.setPadding(dp(12), dp(4), dp(12), dp(4));
        trendTitle = text("", 16, Palette.CYAN);
        trendTitle.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(trendTitle);
        android.widget.HorizontalScrollView chooserScroll = new android.widget.HorizontalScrollView(this);
        LinearLayout chooser = row();
        for (TrendSpec t : TRENDS) {
            Button b = button(t.label, v -> { trendPid = t.pid; showTrend(); });
            trendButtons.put(t.pid, b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(48));
            lp.setMargins(0, dp(4), dp(6), dp(4));
            chooser.addView(b, lp);
        }
        chooserScroll.addView(chooser);
        page.addView(chooserScroll);
        trendView = new TrendView(this);
        trendView.setBackground(cardBackground());
        page.addView(trendView, new LinearLayout.LayoutParams(-1, 0, 1f));
        page.addView(text("Live vom ELM327 · Lücken = kein gültiger Messwert", 12, Palette.MUTED));
        showTrend();
        return page;
    }

    private View buildDtc() {
        LinearLayout page = column();
        page.setPadding(dp(12), dp(4), dp(12), dp(4));
        page.addView(heading("FEHLERCODES"));
        page.addView(text("Mode 03 gespeichert · 07 ausstehend · 0A permanent – nur lesen, kein Löschen", 13, Palette.TEAL));
        LinearLayout card = card();
        dtcState = text(dtcSummary != null ? dtcSummary : "Noch kein Scan. Fehlercodes werden nur auf Knopfdruck gelesen.", 16, Palette.LIGHT);
        dtcState.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        card.addView(dtcState);
        page.addView(card, new LinearLayout.LayoutParams(-1, 0, 1f));
        connectHint = text("Erst verbinden: Handy ins WLAN des ELM327-Adapters, dann oben „Verbinden“.", 13, Palette.WARN);
        page.addView(connectHint);
        LinearLayout actions = row();
        actions.addView(opButton(ObdSession.Op.DTC, "LESEN", v -> session.readDtcs()), weighted());
        actions.addView(button("DETAILS", v -> show(Page.DTC_DETAILS)), weighted());
        actions.addView(opButton(ObdSession.Op.INSPECTION, "HU/AU", v -> { show(Page.INSPECTION); session.runInspectionCheck(); }), weighted());
        page.addView(actions);
        return page;
    }

    private View buildInfo(String host, String port) {
        LinearLayout page = column();
        page.setPadding(dp(12), dp(4), dp(12), dp(4));
        page.addView(heading("SYSTEM / VERBINDUNG"));
        LinearLayout settings = row();
        settings.setGravity(Gravity.CENTER_VERTICAL);
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        hostInput = input(host != null ? host : p.getString("host", "192.168.0.10"), "ELM327-Adresse", false);
        portInput = input(port != null ? port : p.getString("port", "35000"), "Port", true);
        settings.addView(hostInput, new LinearLayout.LayoutParams(0, dp(52), 2f));
        settings.addView(portInput, new LinearLayout.LayoutParams(0, dp(52), 1f));
        page.addView(settings);
        linkState = text("ELM327: getrennt", 15, Palette.LIGHT);
        page.addView(linkState);
        busState = text(profile.busCheck(null), 13, Palette.MUTED);
        page.addView(busState);
        wifiHint = text("", 13, Palette.WARN);
        wifiHint.setVisibility(View.GONE);
        page.addView(wifiHint);
        page.addView(text("Fahrzeug: " + profile.name + " · Bank 1 = Zyl. 1–" + profile.bank1Last + ", Bank 2 = Zyl. "
                + (profile.bank1Last + 1) + "–" + profile.cylinders + " · Leerlauffenster " + profile.idleMinRpm + "–"
                + profile.idleMaxRpm + " U/min", 13, Palette.MUTED));
        stats = text("Keine Diagnosedaten", 13, Palette.MUTED);
        page.addView(stats);
        LinearLayout logActions = row();
        logActions.addView(button("CSV EXPORTIEREN", v -> session.exportCsv(this)), weighted());
        logActions.addView(button("NEUES LOG", v -> session.startNewLog()), weighted());
        page.addView(logActions);
        page.addView(text("Feste Read-only-Befehlsfreigabe · keine Schreib-, Stellglied- oder Löschfunktion", 12, Palette.TEAL));
        ScrollView scroll = new ScrollView(this);
        console = text("", 12, Palette.MUTED);
        console.setTypeface(Typeface.MONOSPACE);
        scroll.addView(console);
        scroll.setBackground(cardBackground());
        scroll.setPadding(dp(8), dp(6), dp(8), dp(6));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        return page;
    }

    private View buildValues() {
        LinearLayout page = column();
        page.setPadding(dp(12), dp(4), dp(12), dp(4));
        page.addView(backRow("SENSOREN / FUEL TRIMS", Page.LIVE));
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = column();
        for (ObdPid pid : ObdPid.defaultPids()) {
            LinearLayout r = row();
            r.setMinimumHeight(dp(40));
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.addView(text(pid.label, 15, Palette.MUTED), new LinearLayout.LayoutParams(0, -2, 1f));
            TextView value = text(ObdSession.INVALID_TEXT, 17, Palette.LIGHT);
            value.setGravity(Gravity.END);
            r.addView(value, new LinearLayout.LayoutParams(0, -2, 1f));
            valueRows.put(pid.pid, value);
            list.addView(r);
        }
        voltage = text("Spannungsbewertung: –", 14, Palette.MUTED);
        driveCycle = text("", 14, Palette.MUTED);
        list.addView(voltage);
        list.addView(driveCycle);
        scroll.addView(list);
        scroll.setBackground(cardBackground());
        scroll.setPadding(dp(10), dp(6), dp(10), dp(6));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout actions = row();
        actions.addView(button("MODE 06", v -> show(Page.MODE06)), weighted());
        actions.addView(button("TRIM-TEST", v -> show(Page.TRIM)), weighted());
        page.addView(actions);
        return page;
    }

    private View buildMode06() {
        LinearLayout page = column();
        page.setPadding(dp(12), dp(4), dp(12), dp(4));
        page.addView(backRow("MODE 06 / AUSSETZER", Page.VALUES));
        page.addView(text("M272: Bank 1 = Zyl. 1–3 · Bank 2 = Zyl. 4–6 · „–“ = nicht gemeldet, nicht „0 Aussetzer“", 13, Palette.MUTED));
        mode06Text = resultText("Aussetzer pro Zylinder oder alle Monitortests manuell lesen.");
        page.addView(scrollCard(mode06Text), new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout actions = row();
        actions.addView(opButton(ObdSession.Op.MISFIRE, "AUSSETZER LESEN", v -> session.runMisfireAnalysis()), weighted());
        actions.addView(opButton(ObdSession.Op.MONITOR_TESTS, "MONITORTESTS", v -> session.runMonitorTests()), weighted());
        page.addView(actions);
        return page;
    }

    private View buildInspection() {
        LinearLayout page = column();
        page.setPadding(dp(12), dp(4), dp(12), dp(4));
        page.addView(backRow("HU/AU-VORCHECK / OBD", Page.DTC));
        page.addView(text("Nur Indikation, keine amtliche Prüfung", 13, Palette.WARN));
        inspectionText = resultText("Vorcheck auf der DTC-Seite starten.");
        page.addView(scrollCard(inspectionText), new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout actions = row();
        actions.addView(opButton(ObdSession.Op.INSPECTION, "VORCHECK STARTEN", v -> session.runInspectionCheck()), weighted());
        page.addView(actions);
        return page;
    }

    private View buildTrim() {
        LinearLayout page = column();
        page.setPadding(dp(12), dp(4), dp(12), dp(4));
        page.addView(backRow("FUEL-TRIM-TEST / WARMLAUF", Page.VALUES));
        page.addView(text("Passiv: nur lesen, Drehzahl wird von Hand gehalten · nur bei stehendem Fahrzeug", 13, Palette.WARN));
        trimStatus = text("Fuel-Trim-Test: bereit", 16, Palette.LIGHT);
        trimStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        warmupStatus = text("Warmlauf-Check: bereit", 16, Palette.LIGHT);
        page.addView(trimStatus);
        page.addView(warmupStatus);
        trimReport = resultText("20 s Leerlauf, danach 15 s bei ca. 2500 U/min. Keine Stellgliedansteuerung.");
        page.addView(scrollCard(trimReport), new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout actions = row();
        fuelButton = button("Fuel-Trim-Test", v -> session.toggleFuelTrimTest());
        warmupButton = button("Warmlauf-Check", v -> session.toggleWarmupCheck());
        actions.addView(fuelButton, weighted());
        actions.addView(warmupButton, weighted());
        page.addView(actions);
        return page;
    }

    private View buildDtcDetails() {
        LinearLayout page = column();
        page.setPadding(dp(12), dp(4), dp(12), dp(4));
        page.addView(backRow("DTC-DETAILS / NUR LESEN", Page.DTC));
        dtcDetails = resultText("Fehlercodes auf der DTC-Seite manuell einlesen. Keine Löschfunktion.");
        page.addView(scrollCard(dtcDetails), new LinearLayout.LayoutParams(-1, 0, 1f));
        return page;
    }

    // ------------------------------------------------------------------ Navigation

    private void show(Page p) {
        page = p;
        for (Map.Entry<Page, View> e : pages.entrySet()) e.getValue().setVisibility(e.getKey() == p ? View.VISIBLE : View.GONE);
        Page tab = tabOf(p);
        for (Map.Entry<Page, Button> e : tabs.entrySet()) {
            boolean sel = e.getKey() == tab;
            e.getValue().setBackground(buttonBackground(sel ? Palette.BUTTON : Palette.TAB, sel));
            e.getValue().setSelected(sel);
            e.getValue().setContentDescription(e.getKey().name() + (sel ? ", ausgewählt" : ""));
        }
    }

    private void showTrend() {
        TrendSpec spec = TRENDS[0];
        for (TrendSpec t : TRENDS) if (t.pid == trendPid) spec = t;
        if (trendTitle != null) trendTitle.setText(spec.label.toUpperCase(java.util.Locale.GERMANY) + "  ·  letzte " + TREND_POINTS + " Werte");
        if (trendView != null) trendView.show(trends.get(spec.pid), spec.min, spec.max, spec.unit);
        for (Map.Entry<Integer, Button> e : trendButtons.entrySet()) {
            boolean sel = e.getKey() == spec.pid;
            e.getValue().setBackground(buttonBackground(sel ? Palette.BUTTON : Palette.TAB, sel));
            e.getValue().setContentDescription(e.getValue().getText() + (sel ? ", angezeigt" : ""));
        }
    }

    // ------------------------------------------------------------------ Verbindung

    private void toggleConnection() {
        if (session.isActive()) {
            session.disconnect();
            return;
        }
        if (!ensurePermissions()) return;
        String host = hostInput.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            status.setText("Ungültiger TCP-Port (INFO-Seite)");
            show(Page.INFO);
            return;
        }
        saveSettings();
        if (!session.connect(this, host, port)) return;
        startForegroundService(new Intent(this, ObdService.class).setAction(ObdService.ACTION_START));
    }

    private boolean ensurePermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(LOCAL_NET_PERMISSION) != PackageManager.PERMISSION_GRANTED)
            needed.add(LOCAL_NET_PERMISSION);
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NET) return;
        if (grantResults.length == 0) return; // request interrupted/cancelled: nothing was refused
        boolean ok = true;
        for (int i = 0; i < grantResults.length && i < permissions.length; i++) {
            if (Manifest.permission.POST_NOTIFICATIONS.equals(permissions[i])) continue;
            ok &= grantResults[i] == PackageManager.PERMISSION_GRANTED;
        }
        if (ok) toggleConnection();
        else Toast.makeText(this, "Ohne Berechtigung für das lokale Netzwerk ist keine Verbindung zum ELM327 möglich", Toast.LENGTH_LONG).show();
    }

    private void saveSettings() {
        if (hostInput == null) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("host", hostInput.getText().toString().trim())
                .putString("port", portInput.getText().toString().trim()).apply();
    }

    /** Diagnose-Knöpfe nur mit Verbindung und nicht während ihrer eigenen Abfrage (statt Toast im Nachhinein). */
    private void updateOpButtons() {
        boolean connected = session.isActive();
        for (Map.Entry<ObdSession.Op, List<Button>> e : opButtons.entrySet()) {
            boolean busy = Boolean.TRUE.equals(busyOps.get(e.getKey()));
            for (Button b : e.getValue()) {
                b.setEnabled(connected && !busy);
                b.setAlpha(connected && !busy ? 1f : 0.45f);
            }
        }
        if (connectHint != null) connectHint.setVisibility(connected ? View.GONE : View.VISIBLE);
    }

    private void updateConnectionUi() {
        boolean active = session.isActive();
        updateOpButtons();
        if (busState != null) {
            Boolean can = active ? session.protocolIsCan() : null;
            busState.setText(profile.busCheck(can));
            busState.setTextColor(can != null && profile.expectCan && !can ? Palette.WARN : Palette.MUTED);
        }
        connectButton.setText(active ? "Trennen" : "Verbinden");
        connectButton.setBackground(buttonBackground(active ? Palette.TAB : Palette.BUTTON, false));
        hostInput.setEnabled(!active);
        portInput.setEnabled(!active);
        // Bildschirm bleibt an, solange eine Sitzung läuft (Halterung im Auto).
        if (active) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ------------------------------------------------------------------ ObdSession.Listener

    @Override
    public void render(ObdSession.State s) {
        status.setText(s.status);
        updateWifiHint(s.status);
        linkState.setText(s.status);
        stats.setText(s.stats.isEmpty() ? "Keine Diagnosedaten" : s.stats);
        console.setText(tail(s.console(), 60));
        for (ObdPid pid : ObdPid.defaultPids()) {
            String text = s.pidTexts.get(pid.pid);
            onPidValue(pid.pid, text == null ? ObdSession.INVALID_TEXT : text);
            if (text == null || ObdSession.INVALID_TEXT.equals(text)) onPidInvalid(pid.pid);
            else restoreGauge(pid.pid);
        }
        onPassive(s);
        onFuelTest(s.fuelButton, s.fuelStatus);
        warmupButton.setText(s.warmupButton);
        warmupStatus.setText(s.warmupStatus);
        for (ObdSession.Op op : ObdSession.Op.values()) onBusy(op, s.busy.contains(op));
        updateConnectionUi();
    }

    @Override
    public void onStatus(String text) {
        status.setText(text);
        linkState.setText(text);
        updateConnectionUi();
        updateWifiHint(text);
    }

    /** Nach fehlgeschlagener Verbindung: der häufigste Grund ist das falsche WLAN (Handy im Heimnetz statt beim Adapter). */
    private void updateWifiHint(String statusText) {
        boolean failed = statusText != null && statusText.contains("fehlgeschlagen");
        wifiHint.setVisibility(failed ? View.VISIBLE : View.GONE);
        if (failed) wifiHint.setText("Tipp: Das Handy muss mit dem WLAN des ELM327-Adapters verbunden sein (Adresse "
                + hostInput.getText().toString().trim() + "). Mobile Daten dürfen an bleiben.");
    }

    @Override
    public void onConsole(String text) {
        console.setText(tail(text, 60));
    }

    @Override
    public void onPidValue(int pid, String text) {
        TextView row = valueRows.get(pid);
        if (row != null) row.setText(text);
        TextView tile = tiles.get(pid);
        if (tile != null) tile.setText(text);
    }

    /** After a rebuild: show the last valid sample of this UI on the new gauge (not a new trend point). */
    private void restoreGauge(int pid) {
        Double v = lastSamples.get(pid);
        GaugeView g = gauges.get(pid);
        if (v != null && g != null) g.setValue(v);
    }

    @Override
    public void onPidSample(int pid, double value) {
        lastSamples.put(pid, value);
        if (pid == 0x0C) lastRpm = value;
        if (pid == 0x0D) lastSpeed = value;
        if (pid == 0x0C || pid == 0x0D) updateIdle();
        GaugeView g = gauges.get(pid);
        if (g != null) g.setValue(value);
        TrendBuffer t = trends.get(pid);
        if (t != null) {
            t.add(value);
            if (pid == trendPid && page == Page.GRAPH) trendView.invalidate();
        }
    }

    @Override
    public void onPidInvalid(int pid) {
        lastSamples.remove(pid);
        if (pid == 0x0C) lastRpm = Double.NaN;
        if (pid == 0x0D) lastSpeed = Double.NaN;
        if (pid == 0x0C || pid == 0x0D) updateIdle();
        GaugeView g = gauges.get(pid);
        if (g != null) g.setInvalid();
        TrendBuffer t = trends.get(pid);
        if (t != null) {
            t.addGap();
            if (pid == trendPid && trendView != null) trendView.invalidate();
        }
    }

    private void updateIdle() {
        if (idleState == null) return;
        String t = profile.idleText(lastRpm, lastSpeed);
        idleState.setText(t);
        idleState.setTextColor(t.startsWith("Leerlauf plausibel") ? Palette.OK : Palette.WARN);
    }

    @Override
    public void onLatency(long ms) { }

    @Override
    public void onStats(String text) {
        stats.setText(text);
        updateConnectionUi();
    }

    @Override
    public void onPassive(ObdSession.State s) {
        operating.setText(s.operatingText);
        operating.setTextColor(s.operatingText.endsWith("–") ? Palette.MUTED : s.operatingColor);
        voltage.setText(s.voltageText);
        driveCycle.setText(s.driveCycleText == null ? "" : s.driveCycleText);
    }

    @Override
    public void onFuelTest(String buttonText, String statusText) {
        fuelButton.setText(buttonText);
        trimStatus.setText(statusText);
    }

    @Override
    public void onBusy(ObdSession.Op op, boolean busy) {
        busyOps.put(op, busy);
        updateOpButtons();
        if (op == ObdSession.Op.DTC && busy) dtcState.setText("Lese Fehlercodes 03 / 07 / 0A …");
        if (op == ObdSession.Op.MISFIRE && busy) mode06Text.setText("Lese Mode 06 (Aussetzer) …");
        if (op == ObdSession.Op.MONITOR_TESTS && busy) mode06Text.setText("Lese Mode-06-Monitortests …");
        if (op == ObdSession.Op.INSPECTION && busy) inspectionText.setText("HU/AU-Vorcheck läuft …");
    }

    @Override
    public void onResult(ObdSession.Op op, String title, String message) {
        handle(ResultRouter.route(op, title), title, message);
    }

    @Override
    public void onDialog(String title, String message) {
        handle(ResultRouter.route(null, title), title, message);
    }

    private void handle(ResultRouter.Target target, String title, String message) {
        String text = title + "\n\n" + message;
        switch (target) {
            case DTC_DETAILS:
                results.put(target, text);
                dtcSummary = firstLines(message, 6);
                dtcState.setText(dtcSummary);
                refreshResults();
                show(Page.DTC_DETAILS);
                break;
            case MODE06: case MONITOR_TESTS: case INSPECTION:
                results.put(target, text);
                refreshResults();
                show(target == ResultRouter.Target.INSPECTION ? Page.INSPECTION : Page.MODE06);
                break;
            case TRIM:
                results.put(target, text);
                refreshResults();
                dialog(title, message); // Anleitung verlangt eine Handlung (z. B. Drehzahl anheben)
                break;
            default:
                dialog(title, message);
        }
    }

    @Override
    public void onToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void refreshResults() {
        if (dtcDetails == null) return;
        String d = results.get(ResultRouter.Target.DTC_DETAILS);
        if (d != null) dtcDetails.setText(d);
        String m = results.get(ResultRouter.Target.MONITOR_TESTS);
        String a = results.get(ResultRouter.Target.MODE06);
        if (a != null || m != null) mode06Text.setText((a != null ? a : "") + (a != null && m != null ? "\n\n" : "") + (m != null ? m : ""));
        String i = results.get(ResultRouter.Target.INSPECTION);
        if (i != null) inspectionText.setText(i);
        String t = results.get(ResultRouter.Target.TRIM);
        if (t != null) trimReport.setText(t);
    }

    private void dialog(String title, String message) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title).setMessage(message).setPositiveButton("OK", null).show();
    }

    // ------------------------------------------------------------------ Bausteine

    private static String tail(String text, int lines) {
        String[] all = text.split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, all.length - lines); i < all.length; i++) b.append(all[i]).append('\n');
        return b.toString();
    }

    private static String firstLines(String text, int lines) {
        String[] all = text.split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(lines, all.length); i++) b.append(all[i]).append('\n');
        if (all.length > lines) b.append("… vollständig unter DETAILS");
        return b.toString().trim();
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        lp.setMargins(dp(3), dp(6), dp(3), dp(4));
        return lp;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private TextView heading(String s) {
        TextView t = text(s, 15, Palette.CYAN);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.06f);
        if (Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        return t;
    }

    private TextView resultText(String initial) {
        TextView t = text(initial, 15, Palette.LIGHT);
        t.setTextIsSelectable(true);
        t.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return t;
    }

    private View scrollCard(TextView content) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        scroll.setBackground(cardBackground());
        scroll.setPadding(dp(10), dp(8), dp(10), dp(8));
        return scroll;
    }

    private LinearLayout backRow(String title, Page back) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        Button b = button("‹ ZURÜCK", v -> show(back));
        b.setContentDescription("Zurück");
        r.addView(b, new LinearLayout.LayoutParams(dp(120), dp(48)));
        TextView h = heading(title);
        h.setPadding(dp(12), 0, 0, 0);
        r.addView(h);
        return r;
    }

    private LinearLayout card() {
        LinearLayout c = column();
        c.setBackground(cardBackground());
        c.setPadding(dp(12), dp(8), dp(12), dp(8));
        return c;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Palette.CARD);
        d.setCornerRadius(dp(10));
        d.setStroke(dp(1), Palette.BORDER);
        return d;
    }

    private GradientDrawable buttonBackground(int color, boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(10));
        d.setStroke(dp(selected ? 2 : 1), selected ? Palette.CYAN : Palette.BORDER);
        return d;
    }

    private Button button(String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Palette.LIGHT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setMaxLines(1);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setBackground(buttonBackground(Palette.BUTTON, false));
        b.setStateListAnimator(null);
        b.setOnClickListener(click);
        return b;
    }

    private Button opButton(ObdSession.Op op, String label, View.OnClickListener click) {
        Button b = button(label, click);
        opButtons.computeIfAbsent(op, k -> new ArrayList<>()).add(b);
        return b;
    }

    private EditText input(String value, String hint, boolean number) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setContentDescription(hint);
        e.setSingleLine(true);
        e.setTextColor(Palette.LIGHT);
        e.setHintTextColor(Palette.MUTED);
        e.setInputType(number ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setBackground(cardBackground());
        e.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        e.setLayoutParams(lp);
        return e;
    }
}
