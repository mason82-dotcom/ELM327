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

import de.roman.mercedesobdmonitor.AnalysisController;
import de.roman.mercedesobdmonitor.ObdPid;
import de.roman.mercedesobdmonitor.ObdService;
import de.roman.mercedesobdmonitor.AdapterChoice;
import de.roman.mercedesobdmonitor.ObdSession;
import de.roman.mercedesobdmonitor.PlanUi;
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
    static final String PREFS = "dashboard";
    /** ADR-006: je Prozess höchstens ein automatischer Verbindungsversuch beim App-Start. */
    private static boolean autoConnectTried;
    private static final String PREF_NOTIFICATIONS_ASKED = "notifications_asked";
    private static final int REQ_NET = 41;
    private static final int TREND_POINTS = 240;

    private enum Page { LIVE, GRAPH, DTC, INFO, VALUES, MODE06, INSPECTION, TRIM, DTC_DETAILS, ANALYSIS, TRIPS, PLAN }

    /** Reiter, unter dem eine Unterseite hervorgehoben wird. */
    private static Page tabOf(Page p) {
        switch (p) {
            case VALUES: case MODE06: case TRIM: case ANALYSIS: case TRIPS: return Page.LIVE;
            case INSPECTION: case DTC_DETAILS: return Page.DTC;
            case PLAN: return Page.INFO;
            default: return p;
        }
    }

    /** Übergeordnete Seite (wie der ZURÜCK-Knopf), null für die Reiter selbst. */
    private static Page parentOf(Page p) {
        switch (p) {
            case VALUES: return Page.LIVE;
            case MODE06: case TRIM: case ANALYSIS: case TRIPS: return Page.VALUES;
            case INSPECTION: case DTC_DETAILS: return Page.DTC;
            case PLAN: return Page.INFO;
            default: return null;
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
    /**
     * Auswertung und Trim-Verlauf (gemeinsamer Kern mit der Monitor-App, Verlauf in {@link #PREFS} wie bisher);
     * der letzte gültige Wert je PID stellt auch die Instrumente nach einem Neuaufbau wieder her.
     */
    private AnalysisController controller;
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
    private Button connectButton, fuelButton, warmupButton, starterButton, dtcCancelButton;
    private TextView starterText;
    private TrendView trendView;
    private TextView trendTitle;
    private EditText hostInput, portInput;
    /** Kühlmittel-/Bordnetzwarnungen über allen Seiten; Zustand und Ton liegen in der Sitzung. */
    private WarningBar warningBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        for (TrendSpec t : TRENDS) trends.put(t.pid, new TrendBuffer(TREND_POINTS));
        controller = new AnalysisController(this, PREFS, 500);
        if (savedInstanceState != null) {
            try { page = Page.valueOf(savedInstanceState.getString("page", "LIVE")); } catch (IllegalArgumentException ignored) { }
            trendPid = savedInstanceState.getInt("trendPid", 0x0C);
        }
        session.attachTestPlan(this);
        session.attachStores(this);
        session.setWarningsEnabled(true);   // Kühlmittel-/Bordnetzwarnungen mit Ton (auch im Hintergrund)
        // ADR-004: Testfreigabe aus den Einstellungen, ausgeliefert aus
        session.setAdr004Enabled(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_ADR004, false));
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
        controller.setListener(() -> { if (page == Page.ANALYSIS) updateAnalysis(); });
        if (page == Page.TRIPS) refreshTrips();   // zurück auf der offenen FAHRTEN-Seite: neue Fahrt nachlesen
        controller.syncConnection(session.sessionId(), session.isActive());
        maybeAutoConnect();
    }

    /**
     * ADR-006: Mit „Automatisch verbinden“ verbindet die App beim Öffnen selbst – einmal je Prozess, nicht nach einem
     * „Trennen“ des Benutzers und nur, wenn keine Sitzung läuft (z. B. schon vom Radiostart).
     */
    private void maybeAutoConnect() {
        if (autoConnectTried) return;
        autoConnectTried = true;   // nur der erste Start des Prozesses zählt, auch wenn der Haken später gesetzt wird
        if (!AdapterChoice.autoConnect(this, PREFS) || session.isActive() || session.wasUserDisconnect()) return;
        connectIfIdle();
    }

    @Override
    protected void onStop() {
        session.clearUiListener(this);
        // While detached no samples arrive: the curves must not bridge that time (review of #4).
        for (TrendBuffer t : trends.values()) t.addGap();
        controller.onStop();
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
        Button mute = button("Ton aus", v -> session.silenceWarning());
        mute.setTextColor(Palette.ON_SIGNAL);
        mute.setBackground(muteBackground());
        warningBar = new WarningBar(this, mute, dp(4));
        warningBar.show(session.currentWarning(), session.warningCanMute());
        root.addView(warningBar.view());

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        pages.put(Page.LIVE, buildLive());
        pages.put(Page.GRAPH, buildGraph());
        pages.put(Page.DTC, buildDtc());
        pages.put(Page.INFO, buildInfo(host, port));
        pages.put(Page.VALUES, buildValues());
        pages.put(Page.MODE06, buildMode06());
        pages.put(Page.TRIPS, buildTrips());
        pages.put(Page.PLAN, buildPlan());
        pages.put(Page.INSPECTION, buildInspection());
        pages.put(Page.TRIM, buildTrim());
        pages.put(Page.DTC_DETAILS, buildDtcDetails());
        pages.put(Page.ANALYSIS, buildAnalysis());
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

        // Kraftstoffkorrektur und Spannung als Kacheln (M272: Bank 1 = Zyl. 1–3, Bank 2 = Zyl. 4–6)
        int[] tilePids = {0x06, 0x08, 0x07, 0x09, 0x42};
        String[] tileNames = {"STFT B1", "STFT B2", "LTFT B1", "LTFT B2", "ECU-Spannung"};
        View[] tileViews = new View[tilePids.length];
        for (int i = 0; i < tilePids.length; i++) {
            LinearLayout card = card();
            card.addView(text(tileNames[i], 12, Palette.MUTED));
            TextView v = text(ObdSession.INVALID_TEXT, 20, Palette.LIGHT);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(v);
            tiles.put(tilePids[i], v);
            tileViews[i] = card;
        }
        operating = text("Betriebszustand: –", 15, Palette.MUTED);
        idleState = text("", 13, Palette.MUTED);
        Button all = button("ALLE WERTE", v -> show(Page.VALUES));

        if (landscape()) {
            // Halterung: Instrumente über die volle Höhe (vorher nur die Resthöhe unter Kacheln und Fußzeile,
            // ~110 dp), Kacheln als Raster rechts, Zustand darunter. Der Read-only-Hinweis steht schon im Kopf.
            LinearLayout body = row();
            body.addView(rpm, new LinearLayout.LayoutParams(0, -1, 1.3f));
            LinearLayout mid = column();
            mid.addView(coolant, new LinearLayout.LayoutParams(-1, 0, 1f));
            mid.addView(speed, new LinearLayout.LayoutParams(-1, 0, 1f));
            body.addView(mid, new LinearLayout.LayoutParams(0, -1, 0.75f));
            // 2 Zeilen × 3 Spalten: bei 3 Zeilen wurde die Wertzeile unten abgeschnitten (Komma sah aus wie ein Punkt)
            int[] order = {0, 1, 4, 2, 3, -1};   // STFT B1, STFT B2, Spannung / LTFT B1, LTFT B2, ALLE WERTE
            LinearLayout grid = column();
            for (int r = 0; r < 2; r++) {
                LinearLayout gridRow = row();
                gridRow.setBaselineAligned(false);
                for (int c = 0; c < 3; c++) {
                    int i = order[r * 3 + c];
                    View cell = i >= 0 ? tileViews[i] : all;
                    if (cell instanceof LinearLayout) ((LinearLayout) cell).setGravity(Gravity.CENTER_VERTICAL);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
                    lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                    gridRow.addView(cell, lp);
                }
                grid.addView(gridRow, new LinearLayout.LayoutParams(-1, 0, 1f));
            }
            body.addView(grid, new LinearLayout.LayoutParams(0, -1, 1.6f));
            page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
            LinearLayout foot = row();
            foot.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout states = column();   // untereinander: ein langer Zustand verdrängt die Leerlaufwarnung nicht
            states.addView(operating);
            states.addView(idleState);
            foot.addView(states, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView note = text("Keine Codierung · kein Löschen · keine Stellglieder", 12, Palette.TEAL);
            note.setGravity(Gravity.END);
            foot.addView(note, new LinearLayout.LayoutParams(-2, -2));
            page.addView(foot);
            return page;
        }

        page.addView(rpm, new LinearLayout.LayoutParams(-1, 0, 1.2f));
        LinearLayout pair = row();
        pair.addView(coolant, new LinearLayout.LayoutParams(0, -1, 1f));
        pair.addView(speed, new LinearLayout.LayoutParams(0, -1, 1f));
        page.addView(pair, new LinearLayout.LayoutParams(-1, 0, 0.9f));
        LinearLayout tileRow = row(), tileRow2 = row();
        for (int i = 0; i < tileViews.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            (i < 3 ? tileRow : tileRow2).addView(tileViews[i], lp);
        }
        page.addView(tileRow);
        page.addView(tileRow2);

        LinearLayout foot = row();
        foot.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout states = column();
        states.addView(operating);
        states.addView(idleState);
        foot.addView(states, new LinearLayout.LayoutParams(0, -2, 1f));
        foot.addView(all, new LinearLayout.LayoutParams(dp(150), dp(52)));
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
        dtcCancelButton = button("STOP", v -> session.cancelDtcScan());
        dtcCancelButton.setContentDescription("Laufenden DTC-Scan abbrechen und Verbindung neu aufbauen");
        dtcCancelButton.setEnabled(false);
        actions.addView(dtcCancelButton, weighted());
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
        hostInput = input(host != null ? host : p.getString(AdapterChoice.KEY_HOST, AdapterChoice.DEFAULT_HOST), "ELM327-Adresse", false);
        portInput = input(port != null ? port : p.getString(AdapterChoice.KEY_PORT, AdapterChoice.DEFAULT_PORT), "Port", true);
        settings.addView(hostInput, new LinearLayout.LayoutParams(0, dp(52), 2f));
        settings.addView(portInput, new LinearLayout.LayoutParams(0, dp(52), 1f));
        page.addView(settings);
        adapterButton = button("", v -> AdapterChoice.pick(this, PREFS, android.R.style.Theme_Material_Dialog_Alert,
                this::updateAdapterLabel));
        adapterButton.setAllCaps(false);
        adapterButton.setContentDescription("ELM327-Adapter wählen: WLAN oder gekoppeltes Bluetooth-Gerät");
        page.addView(adapterButton, new LinearLayout.LayoutParams(-1, dp(48)));
        AdapterChoice.watch(hostInput, portInput, this::updateAdapterLabel);
        updateAdapterLabel();
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
        logActions.addView(button("CSV EXPORT", v -> session.exportCsv(this)), weighted());
        logActions.addView(button("NEUES LOG", v -> session.startNewLog()), weighted());
        logActions.addView(button("PRÜFPLAN", v -> show(Page.PLAN)), weighted());
        page.addView(logActions);
        android.widget.CheckBox adr004 = new android.widget.CheckBox(this);
        adr004.setText("Herstellerabfragen nach ADR-004 (Test): Getriebe-Kennung 1A 86 und Getriebe-Fehler 18 02 FF 00 – Knopf unter ANALYSE");
        adr004.setTextColor(Palette.LIGHT);
        adr004.setTextSize(13);
        adr004.setChecked(session.isAdr004Enabled());
        adr004.setOnCheckedChangeListener((v, checked) -> {
            session.setAdr004Enabled(checked);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ADR004, checked).apply();
            updateAdr004Visibility();
        });
        page.addView(adr004);
        android.widget.CheckBox auto = new android.widget.CheckBox(this);
        auto.setText("Automatisch verbinden – beim Öffnen der App und nach dem Start des Radios (ADR-006)");
        auto.setTextColor(Palette.LIGHT);
        auto.setTextSize(13);
        auto.setChecked(AdapterChoice.autoConnect(this, PREFS));
        auto.setOnCheckedChangeListener((v, checked) -> AdapterChoice.setAutoConnect(this, PREFS, checked));
        page.addView(auto);
        page.addView(text("Feste Read-only-Befehlsfreigabe · keine Schreib-, Stellglied- oder Löschfunktion", 12, Palette.TEAL));
        ScrollView scroll = new ScrollView(this);
        console = text("", 12, Palette.MUTED);
        console.setTypeface(Typeface.MONOSPACE);
        scroll.addView(console);
        scroll.setBackground(cardBackground());
        scroll.setPadding(dp(8), dp(6), dp(8), dp(6));
        if (!landscape()) {
            page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
            return page;
        }
        // Querformat: Einstellungen links (scrollbar – vorher wurden die Log-Knöpfe unten abgeschnitten und das
        // Protokoll bekam keine Höhe), Protokoll rechts über die volle Höhe
        LinearLayout split = row();
        split.setPadding(dp(12), dp(4), dp(12), dp(4));
        page.setPadding(0, 0, dp(8), 0);
        ScrollView left = new ScrollView(this);
        left.addView(page);
        split.addView(left, new LinearLayout.LayoutParams(0, -1, 1.2f));
        split.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1f));
        return split;
    }

    private View buildValues() {
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
        return subPage("SENSOREN / FUEL TRIMS", Page.VALUES, new View[0], new View[0], list,
                button("MODE 06", v -> show(Page.MODE06)),
                button("TRIM-TEST", v -> show(Page.TRIM)),
                button("ANALYSE", v -> show(Page.ANALYSIS)),
                button("FAHRTEN", v -> show(Page.TRIPS)));
    }

    // ------------------------------------------------------------------ Fahrtenbuch

    private TextView tripsText;

    private View buildTrips() {
        tripsText = resultText(controller.tripReport(AnalysisController.TRIP_ROWS));
        tripsText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        Button refresh = button("AKTUALISIEREN", v -> refreshTrips());
        refresh.setContentDescription("Fahrtenbuch aus den Logs aktualisieren");
        Button share = button("TEILEN (CSV)", v -> controller.shareTrips(this));
        share.setContentDescription("Fahrtenbuch als CSV teilen");
        return subPage("FAHRTENBUCH / NUR LESEN", Page.TRIPS,
                new View[] {text("Aus den CSV-Logs dieser App und importierten Logs · Verbrauch aus Luftmasse und Soll-λ "
                        + "(Richtwert, kein Tankbeleg)", 13, Palette.WARN)},
                new View[0], tripsText, refresh, share);
    }

    // ------------------------------------------------------------------ Prüfplan

    private TextView planText;

    private View buildPlan() {
        planText = resultText(session.testPlanChecklist());
        planText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        Button share = button("TEILEN", v -> startActivity(session.testPlanShareIntent("Dashboard " + appVersion())));
        share.setContentDescription("Prüfplan mit allen Ergebnissen als Text teilen");
        Button clear = button("LEEREN", v -> confirmClearPlan());
        clear.setContentDescription("Gespeicherte Prüfplan-Ergebnisse löschen");
        return subPage("PRÜFPLAN / NÄCHSTER TERMIN AM AUTO", Page.PLAN,
                new View[] {text("Hakt die Fahrzeugtests anhand der Ergebnisse ab und sammelt alle Rohantworten in einem "
                        + "Bericht zum Teilen", 13, Palette.WARN)},
                new View[0], planText, share, clear);
    }

    private void refreshPlan() {
        if (planText != null) planText.setText(session.testPlanChecklist());
    }

    private void confirmClearPlan() {
        PlanUi.confirmClear(this, android.R.style.Theme_Material_Dialog_Alert, session, this::refreshPlan);
    }

    private String appVersion() {
        return PlanUi.appVersion(this);
    }

    /** Logs im Hintergrund nachlesen; die Seite zeigt solange den gespeicherten Stand. */
    private void refreshTrips() {
        controller.refreshTripsInto(tripsText, this);
    }


    /**
     * Unterseite mit Zurück-Kopf (Ziel {@link #parentOf}), Hinweiszeilen, festen Statuszeilen, Scrollinhalt und Aktionsknöpfen. Hochformat: Knöpfe als Zeile unten
     * (zweizeilig statt abgeschnitten). Querformat (Halterung): Knöpfe als Spalte rechts und Hinweiszeilen im
     * Scrollbereich – sonst blieben dort nur 2–3 Zeilen Inhalt sichtbar (Emulator-Prüfung 19.11.2026).
     */
    private View subPage(String title, Page self, View[] headerLines, View[] fixedLines, View content, Button... actions) {
        boolean land = landscape();
        LinearLayout main = column();
        main.addView(backRow(title, parentOf(self)));   // ZURÜCK-Knopf und Zurück-Geste haben dasselbe Ziel
        // Live-Status (z. B. Fuel-Trim-Test) bleibt immer sichtbar; nur statische Hinweise scrollen im Querformat mit
        View scrolled = content;
        if (land && headerLines.length > 0) {
            LinearLayout inner = column();
            for (View h : headerLines) inner.addView(h);
            inner.addView(content);
            scrolled = inner;
        } else {
            for (View h : headerLines) main.addView(h);
        }
        for (View f : fixedLines) main.addView(f);
        main.addView(scrollCard(scrolled), new LinearLayout.LayoutParams(-1, 0, 1f));
        if (!land || actions.length == 0) {
            main.setPadding(dp(12), dp(4), dp(12), dp(4));
            if (actions.length > 0) {
                LinearLayout row = row();
                row.setBaselineAligned(false);   // ein zweizeiliger Knopf rutscht sonst nach unten
                for (Button b : actions) {
                    b.setMaxLines(2);
                    if (actions.length >= 4) {   // vier schmale Knöpfe: sonst bricht „TRIM-TEST“ mitten im Wort um
                        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                        b.setPadding(dp(4), 0, dp(4), 0);
                    }
                    row.addView(b, weighted());
                }
                main.addView(row);
            }
            return main;
        }
        LinearLayout page = row();
        page.setPadding(dp(12), dp(4), dp(12), dp(4));
        page.addView(main, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout side = column();
        side.setPadding(dp(6), 0, 0, 0);
        for (Button b : actions) {
            b.setMaxLines(2);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
            lp.setMargins(0, dp(6), 0, 0);
            side.addView(b, lp);
        }
        // scrollbar: ab fünf Knöpfen (ANALYSE mit ADR-004) reicht die Höhe im Querformat nicht
        ScrollView sideScroll = new ScrollView(this);
        sideScroll.setFillViewport(true);
        sideScroll.addView(side);
        page.addView(sideScroll, new LinearLayout.LayoutParams(dp(190), -1));
        return page;
    }

    // ------------------------------------------------------------------ Analyse (C350/M272)

    private TextView analysisText, atfText, profileText, canMonitorText, kwpText;
    private Button kwpButton;
    private static final String PREF_ADR004 = "adr004_enabled";

    private View buildAnalysis() {
        analysisText = resultText("Werte erscheinen, sobald verbunden.");
        analysisText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        atfText = resultText(results.containsKey(ResultRouter.Target.ATF) ? results.get(ResultRouter.Target.ATF)
                : "ATF-Temperatur der 7G-Tronic: auf Knopfdruck (Getriebesteuergerät, ADR-001).");
        profileText = resultText(results.containsKey(ResultRouter.Target.PROFILE) ? results.get(ResultRouter.Target.PROFILE)
                : "Fahrzeugprofil: welche Werte Motor und Getriebe melden – einmal am Fahrzeug lesen.");
        canMonitorText = resultText(results.containsKey(ResultRouter.Target.CAN_MONITOR)
                ? results.get(ResultRouter.Target.CAN_MONITOR)
                : "Bus mitlesen: 5 s passiv zuhören, ob der OBD-Port Bus-Botschaften zeigt (ADR-002, sendet nichts).");
        kwpText = resultText(results.containsKey(ResultRouter.Target.KWP) ? results.get(ResultRouter.Target.KWP)
                : "Kennung + Getriebe-Fehler: Testfreigabe ADR-004 (1A 86, 18 02 FF 00), nur lesend.");
        LinearLayout content = column();
        content.addView(analysisText);
        content.addView(atfText);
        content.addView(profileText);
        content.addView(canMonitorText);
        content.addView(kwpText);
        Button profileButton = opButton(ObdSession.Op.PROFILE, "PROFIL LESEN", v -> session.runVehicleProfile());
        profileButton.setContentDescription("Fahrzeugprofil lesen");
        Button canButton = opButton(ObdSession.Op.CAN_MONITOR, "BUS MITLESEN", v -> session.runCanMonitor());
        canButton.setContentDescription("CAN-Bus fünf Sekunden passiv mitlesen");
        kwpButton = opButton(ObdSession.Op.KWP, "KENNUNG + FEHLER", v -> session.runKwpIdentDtc());
        kwpButton.setContentDescription("Steuergeräte-Kennung und Getriebe-Fehlerspeicher lesen (Testfreigabe ADR-004)");
        updateAdr004Visibility();
        Button importButton = button("LOG IMPORT", v -> pickTrimLog());
        importButton.setContentDescription("CSV-Log importieren (Trim-Verlauf)");
        View page = subPage("ANALYSE / NUR LESEN", Page.ANALYSIS,
                new View[] {text("Aus Standardwerten berechnet · Schwellen sind Richtwerte, keine Herstellerangaben", 13, Palette.WARN)},
                new View[0], content, opButton(ObdSession.Op.ATF, "ATF LESEN", v -> session.runAtfTemperature()), profileButton, canButton, kwpButton, importButton);
        updateAnalysis();
        return page;
    }

    private void updateAnalysis() {
        controller.showIn(analysisText);
    }

    private View buildMode06() {
        mode06Text = resultText("Aussetzer pro Zylinder oder alle Monitortests manuell lesen.");
        return subPage("MODE 06 / AUSSETZER", Page.MODE06,
                new View[] {text("M272: Bank 1 = Zyl. 1–3 · Bank 2 = Zyl. 4–6 · „–“ = nicht gemeldet, nicht „0 Aussetzer“", 13, Palette.MUTED)},
                new View[0], mode06Text,
                opButton(ObdSession.Op.MISFIRE, "AUSSETZER LESEN", v -> session.runMisfireAnalysis()),
                opButton(ObdSession.Op.MONITOR_TESTS, "MONITORTESTS", v -> session.runMonitorTests()));
    }

    private View buildInspection() {
        inspectionText = resultText("Vorcheck auf der DTC-Seite starten.");
        return subPage("HU/AU-VORCHECK / OBD", Page.INSPECTION,
                new View[] {text("Nur Indikation, keine amtliche Prüfung", 13, Palette.WARN)},
                new View[0], inspectionText, opButton(ObdSession.Op.INSPECTION, "VORCHECK STARTEN", v -> session.runInspectionCheck()));
    }

    private View buildTrim() {
        trimStatus = text("Fuel-Trim-Test: bereit", 16, Palette.LIGHT);
        trimStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        warmupStatus = text("Warmlauf-Check: bereit", 16, Palette.LIGHT);
        trimReport = resultText("20 s Leerlauf, danach 15 s bei ca. 2500 U/min. Keine Stellgliedansteuerung.");
        fuelButton = button("Fuel-Trim-Test", v -> session.toggleFuelTrimTest());
        warmupButton = button("Warmlauf-Check", v -> session.toggleWarmupCheck());
        starterText = resultText(results.containsKey(ResultRouter.Target.STARTER) ? results.get(ResultRouter.Target.STARTER)
                : "Starttest: Zündung an, Motor aus, Test starten; bei „JETZT MOTOR STARTEN“ normal anlassen. "
                + "Misst nur die Spannung am OBD-Stecker (ATRV).");
        starterText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        starterButton = button(STARTER_IDLE, v -> session.toggleStarterTest());
        starterButton.setContentDescription("Starter- und Batterietest starten oder abbrechen");
        LinearLayout content = column();
        content.addView(trimReport);
        content.addView(starterText);
        return subPage("TESTS: TRIM / WARMLAUF / START", Page.TRIM,
                new View[] {text("Passiv: nur lesen, Drehzahl wird von Hand gehalten · nur bei stehendem Fahrzeug", 13, Palette.WARN)},
                new View[] {trimStatus, warmupStatus}, content, fuelButton, warmupButton, starterButton);
    }

    private View buildDtcDetails() {
        dtcDetails = resultText("Fehlercodes auf der DTC-Seite manuell einlesen. Keine Löschfunktion.");
        return subPage("DTC-DETAILS / NUR LESEN", Page.DTC_DETAILS, new View[0], new View[0], dtcDetails);
    }

    // ------------------------------------------------------------------ Navigation

    private void show(Page p) {
        boolean entering = page != p;
        page = p;
        if (p == Page.TRIPS && entering) refreshTrips();
        if (p == Page.PLAN && entering) refreshPlan();
        for (Map.Entry<Page, View> e : pages.entrySet()) e.getValue().setVisibility(e.getKey() == p ? View.VISIBLE : View.GONE);
        Page tab = tabOf(p);
        for (Map.Entry<Page, Button> e : tabs.entrySet()) {
            boolean sel = e.getKey() == tab;
            e.getValue().setBackground(buttonBackground(sel ? Palette.BUTTON : Palette.TAB, sel));
            e.getValue().setSelected(sel);
            e.getValue().setContentDescription(e.getKey().name() + (sel ? ", ausgewählt" : ""));
        }
        updateBackHandling();
    }

    /**
     * Systemgeste „Zurück“ auf einer Unterseite führt zur übergeordneten Seite statt aus der App (im Emulator
     * verließ sie vorher das Dashboard mitten in der Fahrt). Ab Android 13 über OnBackInvokedCallback, nur solange
     * eine Unterseite offen ist – auf den Reitern gilt das normale Verhalten des Systems.
     */
    private Object backCallback;

    private void updateBackHandling() {
        if (Build.VERSION.SDK_INT < 33) return;
        android.window.OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
        boolean sub = parentOf(page) != null;
        if (sub && backCallback == null) {
            android.window.OnBackInvokedCallback cb = () -> {
                Page parent = parentOf(page);
                if (parent != null) show(parent);
            };
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            backCallback = cb;
        } else if (!sub && backCallback != null) {
            dispatcher.unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) backCallback);
            backCallback = null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        // Android 8–12 (vor OnBackInvokedCallback): Zurück-Taste auf einer Unterseite
        Page parent = parentOf(page);
        if (Build.VERSION.SDK_INT < 33 && keyCode == android.view.KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0
                && parent != null) {
            show(parent);
            return true;
        }
        return super.onKeyDown(keyCode, event);
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
            controller.connectionEnded();
            return;
        }
        connectIfIdle();
    }

    /** Verbindet, trennt aber nie – auch nicht, wenn inzwischen der Dienst verbunden hat (Review). */
    private void connectIfIdle() {
        if (session.isActive()) return;
        if (!ensurePermissions()) return;
        String host = hostInput.getText().toString().trim();
        Integer port = AdapterChoice.port(this, PREFS, portInput.getText().toString());
        if (port == null) {
            status.setText("Ungültiger TCP-Port (INFO-Seite)");
            show(Page.INFO);
            return;
        }
        saveSettings();
        if (!AdapterChoice.connect(this, session, PREFS, host, port)) return;
        // Neue Verbindung, evtl. anderes Fahrzeug: die alte behält ihren Verlaufseintrag, Karte und Kat-Tendenz neu
        controller.newConnection();
        controller.syncConnection(session.sessionId(), true);
        startForegroundService(new Intent(this, ObdService.class).setAction(ObdService.ACTION_START));
    }

    private boolean ensurePermissions() {
        // je nach Adapter Bluetooth- oder WLAN-Berechtigungen (ADR-005)
        List<String> needed = AdapterChoice.missingPermissions(this, PREFS);
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
        if (requestCode == AdapterChoice.REQ_BT) {
            AdapterChoice.onPermissionResult(this, PREFS, android.R.style.Theme_Material_Dialog_Alert, grantResults,
                    this::updateAdapterLabel);
            return;
        }
        if (requestCode != REQ_NET) return;
        if (grantResults.length == 0) return; // request interrupted/cancelled: nothing was refused
        boolean ok = true;
        for (int i = 0; i < grantResults.length && i < permissions.length; i++) {
            if (Manifest.permission.POST_NOTIFICATIONS.equals(permissions[i])) continue;
            ok &= grantResults[i] == PackageManager.PERMISSION_GRANTED;
        }
        if (ok) connectIfIdle();
        else Toast.makeText(this, AdapterChoice.deniedText(this, PREFS), Toast.LENGTH_LONG).show();
    }

    private static final int REQUEST_TRIM_LOG = 42;   // 41 = REQ_NET (Berechtigungen)

    /** CSV-Log der Monitor-App wählen (Systemdialog, nur Lesen); seine Fahrten kommen in den Trim-Verlauf. */
    private void pickTrimLog() {
        controller.pickLog(this, REQUEST_TRIM_LOG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        controller.onActivityResult(requestCode, REQUEST_TRIM_LOG, resultCode, data,
                () -> controller.showTripsIn(tripsText));
    }

    private Button adapterButton;

    private void updateAdapterLabel() {
        if (adapterButton == null || hostInput == null) return;
        adapterButton.setText(AdapterChoice.label(this, PREFS, hostInput.getText().toString().trim(),
                portInput.getText().toString().trim()) + " · ändern");
    }

    private void saveSettings() {
        if (hostInput == null) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(AdapterChoice.KEY_HOST, hostInput.getText().toString().trim())
                .putString(AdapterChoice.KEY_PORT, portInput.getText().toString().trim()).apply();
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
        if (dtcCancelButton != null) {
            dtcCancelButton.setEnabled(connected && Boolean.TRUE.equals(busyOps.get(ObdSession.Op.DTC))
                    && !session.isDtcCancellationPending());
            dtcCancelButton.setAlpha(dtcCancelButton.isEnabled() ? 1f : 0.45f);
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
        if (starterText != null && text != null && text.startsWith("Starttest:") && session.isStarterTestRunning()) {
            starterText.setText(text.substring("Starttest: ".length()));
        }
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
        Double v = controller.analysis.sample(pid);
        GaugeView g = gauges.get(pid);
        if (v != null && g != null) g.setValue(v);
    }

    @Override
    public void onPidSample(int pid, double value) {
        controller.onSample(pid, value);
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
        controller.onInvalid(pid);
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
    public void onWarning(de.roman.mercedesobdmonitor.LiveWarnings.Warning warning, boolean canMute) {
        if (warningBar != null) warningBar.show(warning, canMute);
    }

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

    private static final String STARTER_IDLE = "Starttest";
    private static final String STARTER_RUNNING = "Starttest abbrechen";

    @Override
    public void onBusy(ObdSession.Op op, boolean busy) {
        busyOps.put(op, busy);
        if (op == ObdSession.Op.STARTER && starterButton != null) {
            starterButton.setText(busy ? STARTER_RUNNING : STARTER_IDLE);
            if (busy) starterText.setText("Starttest läuft …");
        }
        updateOpButtons();
        if (op == ObdSession.Op.DTC) showDtcBusy(busy);
        if (op == ObdSession.Op.MISFIRE && busy) mode06Text.setText("Lese Mode 06 (Aussetzer) …");
        if (op == ObdSession.Op.MONITOR_TESTS && busy) mode06Text.setText("Lese Mode-06-Monitortests …");
        if (op == ObdSession.Op.INSPECTION && busy) inspectionText.setText("HU/AU-Vorcheck läuft …");
    }

    private static final String DTC_READING = "Lese Fehlercodes 03 / 07 / 0A …";
    private static final String DTC_CANCELLING = "DTC-Scan wird abgebrochen …";

    /**
     * DTC-Karte folgt dem Busy-Zustand statt Statustexten (Review #35–#37): STOP, Trennen und Neuverbinden enden
     * alle mit onBusy(DTC, false); ein danach eintreffendes Ergebnis überschreibt den Hinweis.
     */
    private void showDtcBusy(boolean busy) {
        if (dtcState == null) return;
        if (busy) {
            dtcState.setText(session.isDtcCancellationPending() ? DTC_CANCELLING : DTC_READING);
            return;
        }
        String shown = dtcState.getText().toString();
        if (shown.equals(DTC_READING) || shown.equals(DTC_CANCELLING)) {
            dtcState.setText("DTC-Scan ohne Ergebnis beendet. Zum Lesen erneut LESEN wählen.");
        }
    }

    @Override
    public void onResult(ObdSession.Op op, String title, String message) {
        handle(ResultRouter.route(op, title), title, message);
        refreshPlan();
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
            case STARTER:
                results.put(target, message);
                refreshResults();
                show(Page.TRIM);
                break;
            case PROFILE: case ATF: case CAN_MONITOR: case KWP:
                results.put(target, message);
                refreshResults();
                show(Page.ANALYSIS);
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
        String atf = results.get(ResultRouter.Target.ATF);
        if (atf != null && atfText != null) atfText.setText(atf);
        String starter = results.get(ResultRouter.Target.STARTER);
        if (starter != null && starterText != null) starterText.setText(starter);
        String prof = results.get(ResultRouter.Target.PROFILE);
        if (prof != null && profileText != null) profileText.setText(prof);
        String can = results.get(ResultRouter.Target.CAN_MONITOR);
        if (can != null && canMonitorText != null) canMonitorText.setText(can);
        String kwp = results.get(ResultRouter.Target.KWP);
        if (kwp != null && kwpText != null) kwpText.setText(kwp);
    }

    /** ADR-004: Knopf und Ergebnisfeld nur mit eingeschalteter Testfreigabe. */
    private void updateAdr004Visibility() {
        int v = session.isAdr004Enabled() ? View.VISIBLE : View.GONE;
        if (kwpButton != null) kwpButton.setVisibility(v);
        if (kwpText != null) kwpText.setVisibility(v);
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

    private View scrollCard(View content) {
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

    private GradientDrawable muteBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x33FFFFFF);
        d.setCornerRadius(dp(10));
        d.setStroke(dp(2), Palette.ON_SIGNAL);
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
