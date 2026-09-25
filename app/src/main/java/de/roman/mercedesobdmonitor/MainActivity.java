package de.roman.mercedesobdmonitor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class MainActivity extends Activity {
    private static final int REQ_NET = 41;
    private static final String LOCAL_NET_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    private final AtomicBoolean exclusiveRequest = new AtomicBoolean(false);
    private final AtomicInteger sessionGeneration = new AtomicInteger(0);
    private final AtomicLong telemetrySequence = new AtomicLong(0);

    private EditText hostInput;
    private EditText portInput;
    private EditText terminalInput;
    private TextView status;
    private TextView stats;
    private TextView operatingState;
    private TextView voltageState;
    private TextView fuelTestStatus;
    private TextView console;
    private LinearLayout pidBox;
    private Button dtcButton;
    private Button fuelTestButton;
    private Button misfireButton;
    private Button inspectionButton;
    private SparklineView latencyGraph;

    private final Map<Integer, TextView> pidRows = new LinkedHashMap<>();
    private final List<ObdPid> activePids = new ArrayList<>();
    private final CsvLogger csv = new CsvLogger();
    private final Map<Integer, Double> latestValues = new ConcurrentHashMap<>();
    private final Map<Integer, Long> latestSequences = new ConcurrentHashMap<>();
    private final FuelTrimTest fuelTrimTest = new FuelTrimTest();

    private volatile Elm327Client client;
    private volatile boolean userDisconnect;
    private long samples;
    private long totalLatency;
    private long timeouts;
    private long noData;
    private long ioErrors;
    private long parserErrors;
    private long totalTxBytes;
    private long totalRxBytes;
    private int pollCycle;
    private String elmId = "–";
    private String protocol = "–";
    /** null = unbekannt (Parser erkennt automatisch), sonst CAN/Legacy laut ATDPN. */
    private volatile Boolean protocolIsCan = null;
    /** ATDPN-Rohwert der Erstverbindung; beim Reconnect wird das Protokoll fest gesetzt. */
    private volatile String protocolNumber = null;
    private final LinkPolicy.TimeoutTracker pollTimeouts = new LinkPolicy.TimeoutTracker();
    /** Erste Fahrzeuganfrage nach Reconnect bekommt längeren Timeout (Protokollsuche). */
    private volatile boolean firstRequestAfterInit = false;
    private String adapterVoltage = "–";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        loadSettings();
        showStatus("Nicht verbunden");
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(28));
        root.setBackgroundColor(Color.rgb(17, 19, 24));
        scroll.addView(root);

        TextView title = text("Mercedes OBD2 Monitor", 24, Color.WHITE);
        title.setTypeface(null, 1);
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

        status = text("", 15, Color.WHITE);
        status.setPadding(0, dp(8), 0, dp(4));
        root.addView(status);

        stats = text("", 13, Color.LTGRAY);
        root.addView(stats);

        operatingState = text("Betriebszustand: –", 14, Color.LTGRAY);
        operatingState.setPadding(0, dp(7), 0, dp(2));
        root.addView(operatingState);

        voltageState = text("Spannungsbewertung: –", 13, Color.LTGRAY);
        root.addView(voltageState);

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

        latencyGraph = new SparklineView(this);
        root.addView(latencyGraph, new LinearLayout.LayoutParams(-1, dp(135)));

        TextView liveTitle = text("Livewerte", 18, Color.WHITE);
        liveTitle.setTypeface(null, 1);
        liveTitle.setPadding(0, dp(12), 0, dp(6));
        root.addView(liveTitle);

        pidBox = new LinearLayout(this);
        pidBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(pidBox);
        createPidRows();

        LinearLayout logControls = row();
        Button export = button("CSV exportieren");
        Button clear = button("Log leeren");
        logControls.addView(export, weight());
        logControls.addView(clear, weight());
        root.addView(logControls);

        TextView termTitle = text("ELM327-Terminal (Read-Only)", 18, Color.WHITE);
        termTitle.setTypeface(null, 1);
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
        disconnect.setOnClickListener(v -> disconnect(true));
        dtcButton.setOnClickListener(v -> readDtcs());
        fuelTestButton.setOnClickListener(v -> toggleFuelTrimTest());
        misfireButton.setOnClickListener(v -> runExclusiveDiagnosis(misfireButton,
                "Aussetzer-Analyse", "Lese Mode 06 …", this::readMisfires));
        inspectionButton.setOnClickListener(v -> runExclusiveDiagnosis(inspectionButton,
                "HU/AU-Vorab-Check", "Lese Readiness …", this::readInspection));
        send.setOnClickListener(v -> sendTerminal());
        export.setOnClickListener(v -> exportCsv());
        clear.setOnClickListener(v -> {
            csv.clear();
            console.setText("Log gelöscht.\n");
            latencyGraph.clear();
            samples = totalLatency = timeouts = noData = ioErrors = parserErrors = totalTxBytes = totalRxBytes = 0;
            pollCycle = 0;
            latestValues.clear();
            latestSequences.clear();
            fuelTrimTest.cancel();
            fuelTestButton.setText("Fuel-Trim-Test");
            fuelTestStatus.setText("Fuel-Trim-Test: bereit");
            updateStats();
        });

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
        if (exclusiveRequest.get()) {
            Toast.makeText(this, "DTC-Scan läuft gerade", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ensurePermissions()) return;

        final String host = hostInput.getText().toString().trim();
        final int port;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            showStatus("Ungültiger TCP-Port");
            return;
        }

        saveSettings();
        final int session = sessionGeneration.incrementAndGet();
        userDisconnect = false;
        monitoring.set(false);
        latestValues.clear();
        latestSequences.clear();
        fuelTrimTest.cancel();
        closeClient();

        io.execute(() -> connectAndStart(session, host, port));
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
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_NET);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NET) {
            boolean ok = true;
            for (int r : results) ok &= r == PackageManager.PERMISSION_GRANTED;
            if (ok) connectRequested();
            else Toast.makeText(this, "Netzwerkberechtigung wurde nicht erteilt", Toast.LENGTH_LONG).show();
        }
    }

    private void connectAndStart(int session, String host, int port) {
        try {
            if (session != sessionGeneration.get()) return;
            showStatus("Verbinde mit " + host + ":" + port + " …");
            long connectMs = connectWithFallback(host, port, session);
            append("TCP verbunden in " + connectMs + " ms");

            command(session, "ATZ", 3000);
            command(session, "ATE0", 1500);
            command(session, "ATL0", 1500);
            command(session, "ATS0", 1500);
            command(session, "ATH0", 1500);
            command(session, "ATAT1", 1500);
            command(session, "ATSP0", 1500);
            command(session, "ATST64", 1500);

            elmId = clean(command(session, "ATI", 1800).raw);
            adapterVoltage = clean(command(session, "ATRV", 1800).raw);

            detectPids(session);
            String dp = clean(command(session, "ATDP", 1800).raw);
            String dpn = clean(command(session, "ATDPN", 1800).raw);
            protocol = describeProtocol(dp, dpn);
            protocolIsCan = ObdParser.isCanProtocol(dpn);
            protocolNumber = dpn;
            pollTimeouts.reset();
            showStatus("Verbunden · " + elmId + " · " + protocol);
            append("Adapter: " + elmId);
            append("Protokoll: " + protocol);
            append("Versorgung: " + adapterVoltage);
            if (session != sessionGeneration.get()) return;
            monitoring.set(true);
            monitorLoop(session, host, port);
        } catch (Exception e) {
            if (session != sessionGeneration.get()) return;
            ioErrors++;
            append("Verbindungsfehler: " + e.getMessage());
            showStatus("Verbindung fehlgeschlagen");
            closeClient();
            updateStats();
        }
    }

    private void detectPids(int session) throws IOException {
        Set<Integer> supported = new java.util.HashSet<>();
        Elm327Client.CommandResult p0 = command(session, "0100", 2500);
        supported.addAll(ObdParser.supportedPids(p0.raw, 0x00));
        if (supported.contains(0x20)) {
            Elm327Client.CommandResult p20 = command(session, "0120", 2500);
            supported.addAll(ObdParser.supportedPids(p20.raw, 0x20));
        }
        if (supported.contains(0x40)) {
            Elm327Client.CommandResult p40 = command(session, "0140", 2500);
            supported.addAll(ObdParser.supportedPids(p40.raw, 0x40));
        }

        activePids.clear();
        for (ObdPid pid : ObdPid.defaultPids()) {
            if (supported.isEmpty() || supported.contains(pid.pid)) activePids.add(pid);
        }
        append("Aktive Live-PIDs: " + activePids.size());
    }

    private void monitorLoop(int session, String host, int port) {
        pollCycle = 0;
        while (monitoring.get() && !userDisconnect && session == sessionGeneration.get()) {
            if (exclusiveRequest.get()) {
                sleepQuiet(40);
                continue;
            }

            Elm327Client c = client;
            if (c == null || !c.isConnected()) {
                reconnectAfterFailure(session, host, port);
                continue;
            }

            int cycle = ++pollCycle;
            for (ObdPid pid : new ArrayList<>(activePids)) {
                if (!monitoring.get() || userDisconnect || exclusiveRequest.get()
                        || session != sessionGeneration.get()) break;
                if (!PollSchedule.shouldPoll(pid.pid, cycle, fuelTrimTest.isRunning())) continue;

                try {
                    int timeoutMs = firstRequestAfterInit ? LinkPolicy.FIRST_REQUEST_TIMEOUT_MS : 1800;
                    Elm327Client.CommandResult r = command(session, pid.command(), timeoutMs);
                    firstRequestAfterInit = false;
                    pollTimeouts.onResponse();
                    if (ObdParser.isNoData(r.raw)) {
                        noData++;
                        continue;
                    }
                    Double value = pid.parse(r.raw);
                    if (value == null) {
                        parserErrors++;
                        continue;
                    }
                    samples++;
                    totalLatency += r.elapsedMs;
                    if (session != sessionGeneration.get()) break;
                    latestValues.put(pid.pid, value);
                    latestSequences.put(pid.pid, telemetrySequence.incrementAndGet());
                    csv.record(System.currentTimeMillis(), pid, value, r.elapsedMs, r.raw);
                    ui.post(() -> {
                        TextView v = pidRows.get(pid.pid);
                        if (v != null) v.setText(pid.format(value));
                        latencyGraph.addValue(r.elapsedMs);
                        updateStats();
                    });
                } catch (SocketTimeoutException e) {
                    timeouts++;
                    firstRequestAfterInit = false;
                    // Auf den verspäteten '>'-Prompt warten; erst bei wiederholtem
                    // Timeout oder fehlgeschlagener Resynchronisation neu verbinden.
                    boolean resynced = c.resync(1500);
                    if (pollTimeouts.onTimeout(resynced)) {
                        append("Timeout bei " + pid.command() + " (" + pollTimeouts.consecutive()
                                + "× in Folge" + (resynced ? "" : ", kein Prompt") + ") · Verbindung wird neu aufgebaut");
                        pollTimeouts.reset();
                        cancelFuelTrimForConnectionLoss("Timeout");
                        closeClientIfCurrent(c);
                        updateStats();
                        break;
                    }
                    append("Timeout bei " + pid.command() + " · Prompt resynchronisiert, Verbindung bleibt");
                    updateStats();
                    continue;
                } catch (IOException e) {
                    ioErrors++;
                    append("I/O: " + e.getMessage());
                    cancelFuelTrimForConnectionLoss("Verbindungsfehler");
                    closeClientIfCurrent(c);
                    updateStats();
                    break;
                }
                sleepQuiet(25);
            }
            updatePassiveDiagnostics();
            sleepQuiet(70);
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void reconnectAfterFailure(int session, String host, int port) {
        if (userDisconnect || !monitoring.get() || session != sessionGeneration.get()) return;
        showStatus("Verbindung unterbrochen · Reconnect …");
        try {
            sleepQuiet(2000);
            if (session != sessionGeneration.get()) return;
            connectWithFallback(host, port, session);
            for (String init : LinkPolicy.reconnectInit(protocolNumber)) {
                command(session, init, 1500);
            }
            pollTimeouts.reset();
            firstRequestAfterInit = true;
            latestValues.clear();
            latestSequences.clear();
            showStatus("Wieder verbunden · " + protocol);
            append("Auto-Reconnect erfolgreich");
        } catch (Exception e) {
            if (session != sessionGeneration.get()) return;
            ioErrors++;
            closeClientForSession(session);
            updateStats();
        }
    }

    private Elm327Client.CommandResult command(int session, String cmd, int timeoutMs) throws IOException {
        final Elm327Client c;
        synchronized (this) {
            if (session != sessionGeneration.get()) throw new IOException("Veraltete Diagnose-Session");
            c = client;
        }
        if (c == null) throw new IOException("Nicht verbunden");
        return c.sendCommand(cmd, timeoutMs);
    }

    private void readDtcs() {
        Elm327Client c = client;
        if (c == null || !c.isConnected()) {
            append("DTC: nicht verbunden");
            Toast.makeText(this, "Keine ELM327-Verbindung", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!exclusiveRequest.compareAndSet(false, true)) {
            Toast.makeText(this, "Diagnosevorgang läuft bereits", Toast.LENGTH_SHORT).show();
            return;
        }

        ui.post(() -> {
            dtcButton.setEnabled(false);
            dtcButton.setText("Lese DTC…");
            status.setText("DTC-Scan läuft …");
        });

        io.execute(() -> {
            String report;
            try {
                StringBuilder sb = new StringBuilder();
                List<String> allCodes = new ArrayList<>();
                synchronized (c) {
                    sb.append("Generische OBD-II-Fehlercodes\n\n");
                    sb.append(readDtcMode(c, "Gespeichert", "03", 0x43, allCodes));
                    sleepQuiet(180);
                    sb.append("\n\n").append(readDtcMode(c, "Pending", "07", 0x47, allCodes));
                    sleepQuiet(180);
                    sb.append("\n\n").append(readDtcMode(c, "Permanent", "0A", 0x4A, allCodes));
                    sleepQuiet(250);

                    // Nach dem DTC-Scan einen harmlosen Read-Only-Request als Verbindungsprobe.
                    try {
                        c.sendCommand("0100", 2200);
                    } catch (IOException probeError) {
                        append("DTC-Nachtest: Verbindung wird neu aufgebaut – " + probeError.getMessage());
                        closeClient();
                    }
                }
                sb.append(m272Section(allCodes));
                report = sb.toString();
                append("DTC-Scan abgeschlossen");
            } catch (Exception e) {
                report = "DTC-Scan fehlgeschlagen:\n" + e.getMessage();
                append("DTC-Fehler: " + e.getMessage());
            } finally {
                exclusiveRequest.set(false);
            }

            final String result = report;
            ui.post(() -> {
                dtcButton.setEnabled(true);
                dtcButton.setText("DTC");
                Elm327Client current = client;
                status.setText(current != null && current.isConnected()
                        ? "Verbunden · " + elmId + " · " + protocol
                        : "DTC-Scan beendet · Reconnect läuft …");
                new AlertDialog.Builder(this)
                        .setTitle("OBD-II Fehlercodes")
                        .setMessage(result)
                        .setPositiveButton("OK", null)
                        .show();
            });
        });
    }

    private static String m272Section(List<String> codes) {
        List<String> seen = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (String code : codes) {
            if (seen.contains(code)) continue;
            seen.add(code);
            String h = M272Hints.hint(code);
            if (h != null) sb.append("\n\n").append(code).append(": ").append(h);
        }
        for (String p : M272Hints.patterns(seen)) sb.append("\n\n⚑ ").append(p);
        if (sb.length() == 0) return "";
        return "\n\n— M272-Werkstatthinweise (typische Ursachen, keine Diagnose) —" + sb;
    }

    private String readDtcMode(Elm327Client c, String label, String cmd, int responseMode,
                               List<String> collected) {
        try {
            Elm327Client.CommandResult r = c.sendCommand(cmd, 3500);
            String raw = clean(r.raw);
            append("DTC " + label + " [" + cmd + "]: " + raw);
            List<String> codes = ObdParser.dtcs(r.raw, responseMode, protocolIsCan);
            if (collected != null) collected.addAll(codes);
            if (codes.isEmpty()) {
                if (raw.isEmpty()) return label + ": keine Antwort";
                if (ObdParser.isNoData(r.raw)) {
                    return responseMode == 0x4A
                            ? label + ": nicht unterstützt / NO DATA"
                            : label + ": keine Fehlercodes gemeldet";
                }
                if (raw.replace(" ", "").matches("^(43|47)(00)+(\\s*(43|47)(00)+)*$")) {
                    return label + ": keine Fehlercodes gemeldet";
                }
                return label + ": keine Fehlercodes erkannt\nRohantwort: " + raw;
            }
            StringBuilder sb = new StringBuilder(label).append(":");
            for (String code : codes) {
                sb.append("\n• ").append(DtcDescriptions.describe(code));
            }
            return sb.toString();
        } catch (SocketTimeoutException e) {
            timeouts++;
            append("DTC " + label + ": Timeout · Verbindung wird neu synchronisiert");
            cancelFuelTrimForConnectionLoss("DTC-Timeout");
            closeClientIfCurrent(c);
            return label + ": Timeout · Verbindung wird neu synchronisiert";
        } catch (IOException e) {
            ioErrors++;
            closeClientIfCurrent(c);
            return label + ": Kommunikationsfehler – " + e.getMessage();
        }
    }

    private interface Diagnosis {
        String run(Elm327Client c) throws IOException;
    }

    /**
     * Führt eine Read-Only-Diagnose mit exklusivem Adapterzugriff aus (Live-Polling pausiert)
     * und zeigt das Ergebnis als Dialog. Timeouts werden wie beim DTC-Scan behandelt.
     */
    private void runExclusiveDiagnosis(Button button, String title, String busyText, Diagnosis job) {
        Elm327Client c = client;
        if (c == null || !c.isConnected()) {
            Toast.makeText(this, "Keine ELM327-Verbindung", Toast.LENGTH_SHORT).show();
            return;
        }
        if (fuelTrimTest.isRunning()) {
            Toast.makeText(this, "Fuel-Trim-Test läuft – bitte zuerst beenden", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!exclusiveRequest.compareAndSet(false, true)) {
            Toast.makeText(this, "Diagnosevorgang läuft bereits", Toast.LENGTH_SHORT).show();
            return;
        }
        final CharSequence label = button.getText();
        button.setEnabled(false);
        button.setText(busyText);
        status.setText(title + " läuft …");

        io.execute(() -> {
            String report;
            try {
                synchronized (c) {
                    report = job.run(c);
                }
                append(title + " abgeschlossen");
            } catch (SocketTimeoutException e) {
                timeouts++;
                append(title + ": Timeout · Verbindung wird neu synchronisiert");
                closeClientIfCurrent(c);
                report = title + " abgebrochen: Timeout.\nDie Verbindung wird neu aufgebaut.";
            } catch (IOException e) {
                ioErrors++;
                closeClientIfCurrent(c);
                report = title + " fehlgeschlagen:\n" + e.getMessage();
            } catch (RuntimeException e) {
                parserErrors++;
                report = title + " fehlgeschlagen (Auswertung):\n" + e;
            } finally {
                exclusiveRequest.set(false);
            }
            final String result = report;
            ui.post(() -> {
                button.setEnabled(true);
                button.setText(label);
                Elm327Client current = client;
                status.setText(current != null && current.isConnected()
                        ? "Verbunden · " + elmId + " · " + protocol
                        : title + " beendet · Reconnect läuft …");
                updateStats();
                new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setMessage(result)
                        .setPositiveButton("OK", null)
                        .show();
            });
        });
    }

    private Elm327Client.CommandResult diagCommand(Elm327Client c, String cmd, int timeoutMs) throws IOException {
        Elm327Client.CommandResult r = c.sendCommand(cmd, timeoutMs);
        append(cmd + " → " + clean(r.raw));
        sleepQuiet(60);
        return r;
    }

    /** Mode 06: OBDMID A2–A7 = Aussetzer Zylinder 1–6 (M272). */
    private String readMisfires(Elm327Client c) throws IOException {
        if (Boolean.FALSE.equals(protocolIsCan)) {
            return "Mode 06 wird nur für CAN (ISO 15765-4) ausgewertet. Erkanntes Protokoll: " + protocol;
        }
        Elm327Client.CommandResult sup = diagCommand(c, Mode06.command(Mode06.MID_SUPPORT_A0), 3000);
        Set<Integer> mids = Mode06.supportedMids(sup.raw, Mode06.MID_SUPPORT_A0);
        boolean bitmapKnown = !mids.isEmpty();

        List<Mode06.Misfire> list = new ArrayList<>();
        for (int cyl = 1; cyl <= 6; cyl++) {
            int mid = Mode06.MID_MISFIRE_CYL1 + cyl - 1;
            if (bitmapKnown && !mids.contains(mid)) continue;
            Elm327Client.CommandResult r = diagCommand(c, Mode06.command(mid), 3000);
            if (ObdParser.isNoData(r.raw)) continue;
            Mode06.Misfire m = Mode06.misfire(r.raw, cyl);
            if (m != null) list.add(m);
        }
        String report = Mode06.misfireReport(list);
        if (!bitmapKnown) {
            report += "\n\nHinweis: Keine Mode-06-Bitmap (06A0) erhalten – Zylinder wurden direkt abgefragt.";
        }
        return report;
    }

    /** HU/AU-Vorab-Check: 01 01, 03/07/0A, 01 21/30/31, Freeze Frame. */
    private String readInspection(Elm327Client c) throws IOException {
        InspectionCheck.Input in = new InspectionCheck.Input();
        in.readiness = Readiness.parse(diagCommand(c, "0101", 3000).raw);

        in.stored = ObdParser.dtcs(diagCommand(c, "03", 3500).raw, 0x43, protocolIsCan);
        in.pending = ObdParser.dtcs(diagCommand(c, "07", 3500).raw, 0x47, protocolIsCan);
        Elm327Client.CommandResult perm = diagCommand(c, "0A", 3500);
        in.permanentSupported = !ObdParser.isNoData(perm.raw);
        in.permanent = ObdParser.dtcs(perm.raw, 0x4A, protocolIsCan);

        in.kmWithMil = optionalWord(c, 0x21);
        in.warmupsSinceCleared = optionalByte(c, 0x30);
        in.kmSinceCleared = optionalWord(c, 0x31);

        Elm327Client.CommandResult ffDtc = diagCommand(c, "020200", 3000);
        in.freezeFrameDtc = InspectionCheck.freezeFrameDtc(ffDtc.raw);
        if (in.freezeFrameDtc != null) {
            for (ObdPid pid : ObdPid.defaultPids()) {
                if (!activePids.isEmpty() && !containsPid(pid.pid)) continue;
                Elm327Client.CommandResult r = diagCommand(c,
                        String.format(Locale.US, "02%02X00", pid.pid), 2500);
                if (ObdParser.isNoData(r.raw)) continue;
                Double v = pid.parseFreezeFrame(r.raw);
                if (v != null) in.freezeFrame.add(pid.label + ": " + pid.format(v));
            }
        }
        return InspectionCheck.report(in);
    }

    private boolean containsPid(int pid) {
        for (ObdPid p : new ArrayList<>(activePids)) if (p.pid == pid) return true;
        return false;
    }

    private Integer optionalWord(Elm327Client c, int pid) throws IOException {
        Elm327Client.CommandResult r = diagCommand(c, String.format(Locale.US, "01%02X", pid), 2500);
        return InspectionCheck.word(ObdParser.mode01Data(r.raw, pid, 2));
    }

    private Integer optionalByte(Elm327Client c, int pid) throws IOException {
        Elm327Client.CommandResult r = diagCommand(c, String.format(Locale.US, "01%02X", pid), 2500);
        return InspectionCheck.byteValue(ObdParser.mode01Data(r.raw, pid, 1));
    }

    private void toggleFuelTrimTest() {
        if (fuelTrimTest.isRunning()) {
            fuelTrimTest.cancel();
            fuelTestButton.setText("Fuel-Trim-Test");
            fuelTestStatus.setText("Fuel-Trim-Test: abgebrochen");
            append("Fuel-Trim-Test abgebrochen");
            return;
        }

        Elm327Client c = client;
        if (c == null || !c.isConnected()) {
            Toast.makeText(this, "Keine ELM327-Verbindung", Toast.LENGTH_SHORT).show();
            return;
        }

        Double rpm = latestValues.get(0x0C);
        if (rpm == null || rpm < 300.0) {
            new AlertDialog.Builder(this)
                    .setTitle("Fuel-Trim-Test")
                    .setMessage("Der Motor muss laufen. Bitte Motor starten und anschließend den Test erneut beginnen.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        if (latestValues.get(0x06) == null || latestValues.get(0x07) == null
                || latestValues.get(0x08) == null || latestValues.get(0x09) == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Fuel-Trim-Test")
                    .setMessage("STFT/LTFT für beide Bänke sind noch nicht vollständig verfügbar. "
                            + "Bitte die Live-Daten kurz weiterlaufen lassen und den Test erneut starten.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        Double coolant = latestValues.get(0x05);
        if (coolant == null || coolant < 80.0) {
            String value = coolant == null ? "unbekannt" : String.format(Locale.GERMANY, "%.0f °C", coolant);
            new AlertDialog.Builder(this)
                    .setTitle("Motor noch nicht warm")
                    .setMessage("Kühlmittel aktuell: " + value
                            + "\n\nFür einen aussagekräftigen Fuel-Trim-Test werden mindestens etwa 80 °C empfohlen. "
                            + "Der Test wurde noch nicht gestartet.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        fuelTrimTest.start();
        fuelTestButton.setText("Test abbrechen");
        fuelTestStatus.setText("Fuel-Trim-Test: Leerlauf stabilisieren …");
        append("Passiver Fuel-Trim-Test gestartet");
        new AlertDialog.Builder(this)
                .setTitle("Fuel-Trim-Test gestartet")
                .setMessage("1. Fahrzeug stehen lassen und stabilen Leerlauf halten.\n"
                        + "2. Die App misst automatisch 20 Sekunden.\n"
                        + "3. Danach wirst du aufgefordert, die Drehzahl manuell bei ca. 2500 U/min zu halten.\n\n"
                        + "Die App steuert keinerlei Stellglieder und verändert keine Fahrzeugkonfiguration.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void updatePassiveDiagnostics() {
        Double rpmValue = latestValues.get(0x0C);
        Double voltageValue = latestValues.get(0x42);

        final boolean koeo = rpmValue != null && rpmValue < 100.0;
        final boolean koer = rpmValue != null && rpmValue >= 300.0;

        final String stateText;
        final int stateColor;
        if (koeo) {
            stateText = "Betriebszustand: KOEO · Zündung an / Motor aus";
            stateColor = Color.rgb(255, 193, 7);
        } else if (koer) {
            stateText = "Betriebszustand: KOER · Motor läuft";
            stateColor = Color.rgb(129, 199, 132);
        } else if (rpmValue == null) {
            stateText = "Betriebszustand: noch keine Drehzahlinformation";
            stateColor = Color.LTGRAY;
        } else {
            stateText = "Betriebszustand: Startphase / unklar";
            stateColor = Color.LTGRAY;
        }

        final String voltageText = evaluateVoltage(voltageValue, koeo, koer);

        FuelTrimTest.Update testUpdate = fuelTrimTest.tick(
                latestValues, latestSequences, SystemClock.elapsedRealtime());

        ui.post(() -> {
            operatingState.setText(stateText);
            operatingState.setTextColor(stateColor);
            voltageState.setText(voltageText);

            TextView stft1 = pidRows.get(0x06);
            TextView stft2 = pidRows.get(0x08);
            TextView lambda = pidRows.get(0x44);
            int liveColor = koeo ? Color.GRAY : Color.WHITE;
            if (stft1 != null) stft1.setTextColor(liveColor);
            if (stft2 != null) stft2.setTextColor(liveColor);
            if (lambda != null) lambda.setTextColor(liveColor);

            if (koeo) {
                fuelTestStatus.setText("Fuel-Trim-Test: Motor aus · STFT/Soll-Lambda derzeit nicht bewerten");
            } else if (fuelTrimTest.isRunning() || fuelTrimTest.getStage() == FuelTrimTest.Stage.DONE
                    || fuelTrimTest.getStage() == FuelTrimTest.Stage.FAILED) {
                fuelTestStatus.setText("Fuel-Trim-Test: " + testUpdate.status);
            } else {
                fuelTestStatus.setText("Fuel-Trim-Test: bereit");
            }

            if (testUpdate.prompt2500) {
                new AlertDialog.Builder(this)
                        .setTitle("Leerlaufmessung abgeschlossen")
                        .setMessage("Jetzt die Motordrehzahl manuell auf etwa 2300–2700 U/min anheben und konstant halten. "
                                + "Die zweite Messphase startet automatisch, sobald die Drehzahl stabil ist.")
                        .setPositiveButton("OK", null)
                        .show();
            }

            if (testUpdate.failed) {
                fuelTestButton.setText("Fuel-Trim-Test");
                fuelTestStatus.setText("Fuel-Trim-Test: " + testUpdate.status);
                append("Fuel-Trim-Test " + testUpdate.status);
            }

            if (testUpdate.completed && testUpdate.report != null) {
                fuelTestButton.setText("Fuel-Trim-Test");
                fuelTestStatus.setText("Fuel-Trim-Test: abgeschlossen");
                append("Fuel-Trim-Test abgeschlossen");
                new AlertDialog.Builder(this)
                        .setTitle("Fuel-Trim-Auswertung")
                        .setMessage(testUpdate.report)
                        .setPositiveButton("OK", null)
                        .show();
            }
        });
    }

    private static String evaluateVoltage(Double voltage, boolean koeo, boolean koer) {
        if (voltage == null || Double.isNaN(voltage)) {
            return "Spannungsbewertung: noch kein PID-0142-Wert";
        }

        if (koeo) {
            if (voltage < 11.8) {
                return String.format(Locale.GERMANY,
                        "Spannungsbewertung: %.3f V · sehr niedrig unter Zündungslast · Batterie an den Polen prüfen",
                        voltage);
            }
            if (voltage < 12.2) {
                return String.format(Locale.GERMANY,
                        "Spannungsbewertung: %.3f V · niedrig unter Zündungslast · Batteriezustand prüfen",
                        voltage);
            }
            if (voltage < 12.5) {
                return String.format(Locale.GERMANY,
                        "Spannungsbewertung: %.3f V · mäßig unter Zündungslast",
                        voltage);
            }
            return String.format(Locale.GERMANY,
                    "Spannungsbewertung: %.3f V · unter Zündungslast plausibel",
                    voltage);
        }

        if (koer) {
            if (voltage < 13.2) {
                return String.format(Locale.GERMANY,
                        "Spannungsbewertung: %.3f V · Ladespannung auffällig niedrig",
                        voltage);
            }
            if (voltage <= 14.9) {
                return String.format(Locale.GERMANY,
                        "Spannungsbewertung: %.3f V · Ladespannung plausibel",
                        voltage);
            }
            return String.format(Locale.GERMANY,
                    "Spannungsbewertung: %.3f V · Ladespannung auffällig hoch",
                    voltage);
        }

        return String.format(Locale.GERMANY,
                "Spannungsbewertung: %.3f V · Betriebszustand noch unklar",
                voltage);
    }

    private void sendTerminal() {
        if (exclusiveRequest.get()) {
            Toast.makeText(this, "DTC-Scan läuft gerade", Toast.LENGTH_SHORT).show();
            return;
        }
        final String cmd = terminalInput.getText().toString().trim();
        final int session = sessionGeneration.get();
        if (cmd.isEmpty()) return;
        if (!CommandSafety.isAllowed(cmd)) {
            String reason = CommandSafety.blockedReason(cmd);
            append("BLOCKIERT > " + cmd + " · " + reason);
            Toast.makeText(this, "Read-Only-Schutz: " + reason, Toast.LENGTH_LONG).show();
            return;
        }
        io.execute(() -> {
            try {
                append("> " + cmd);
                append(command(session, cmd, 4000).raw);
            } catch (IOException e) {
                append("Terminalfehler: " + e.getMessage());
            }
        });
    }

    private void exportCsv() {
        io.execute(() -> {
            try {
                android.net.Uri uri = csv.export(this);
                ui.post(() -> Toast.makeText(this, "CSV gespeichert: " + uri, Toast.LENGTH_LONG).show());
            } catch (IOException e) {
                ui.post(() -> Toast.makeText(this, "CSV-Export fehlgeschlagen: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private long connectWithFallback(String host, int port, int session) throws IOException {
        IOException standardRouteError = null;

        // Auf dem OnePlus 13R routet Android die lokale 192.168.0.x-Strecke korrekt über WLAN.
        // Diese Variante vermeidet den auf OxygenOS beobachteten EPERM-Fehler beim Network-SocketFactory-Binding.
        if (session != sessionGeneration.get()) throw new IOException("Veraltete Diagnose-Session");
        Elm327Client direct = new Elm327Client(host, port, null);
        try {
            long ms = direct.connect(3500);
            installClientForSession(direct, session);
            append("Android-Standardroute verwendet");
            return ms;
        } catch (IOException e) {
            direct.close();
            standardRouteError = e;
            append("Standardroute fehlgeschlagen: " + e.getMessage());
        }

        IOException last = standardRouteError;
        for (Network network : findWifiNetworks()) {
            if (session != sessionGeneration.get()) throw new IOException("Veraltete Diagnose-Session");
            Elm327Client candidate = new Elm327Client(host, port, network);
            try {
                long ms = candidate.connect(3500);
                installClientForSession(candidate, session);
                append("Fallback über WLAN-Netz " + network);
                return ms;
            } catch (IOException e) {
                candidate.close();
                last = e;
                append("WLAN-Netz " + network + " verworfen: " + e.getMessage());
            }
        }

        throw last != null ? last : new IOException("Keine Route zum ELM327 verfügbar");
    }

    private List<Network> findWifiNetworks() {
        List<Network> out = new ArrayList<>();
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return out;

        Network active = cm.getActiveNetwork();
        if (active != null) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                out.add(active);
            }
        }

        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !out.contains(network)) {
                out.add(network);
            }
        }
        return out;
    }

    private static String describeProtocol(String dp, String dpn) {
        String code = dpn == null ? "" : dpn.trim().toUpperCase(Locale.US);
        if (code.startsWith("A")) code = code.substring(1);
        String decoded = switch (code) {
            case "1" -> "SAE J1850 PWM";
            case "2" -> "SAE J1850 VPW";
            case "3" -> "ISO 9141-2";
            case "4" -> "ISO 14230-4 KWP (5-baud)";
            case "5" -> "ISO 14230-4 KWP (fast init)";
            case "6" -> "ISO 15765-4 CAN 11 bit / 500 kbit/s";
            case "7" -> "ISO 15765-4 CAN 29 bit / 500 kbit/s";
            case "8" -> "ISO 15765-4 CAN 11 bit / 250 kbit/s";
            case "9" -> "ISO 15765-4 CAN 29 bit / 250 kbit/s";
            default -> "";
        };
        if (!decoded.isEmpty()) return decoded + " (ELM " + code + ")";
        if (dp != null && !dp.isBlank()) return dp + (code.isEmpty() ? "" : " (" + code + ")");
        return code.isEmpty() ? "Unbekannt" : "ELM-Protokoll " + code;
    }

    private void disconnect(boolean fromUser) {
        if (fromUser) {
            sessionGeneration.incrementAndGet();
            userDisconnect = true;
            monitoring.set(false);
            latestValues.clear();
            latestSequences.clear();
            fuelTrimTest.cancel();
            if (fuelTestButton != null) {
                ui.post(() -> {
                    fuelTestButton.setText("Fuel-Trim-Test");
                    fuelTestStatus.setText("Fuel-Trim-Test: bereit");
                });
            }
            showStatus("Getrennt");
            append("Verbindung getrennt");
        }
        closeClient();
    }

    private synchronized void installClientForSession(Elm327Client candidate, int session) throws IOException {
        if (session != sessionGeneration.get()) {
            candidate.close();
            throw new IOException("Verbindungsversuch durch neuere Session ersetzt");
        }
        Elm327Client old = client;
        client = candidate;
        if (old != null && old != candidate) {
            totalTxBytes += old.getTxBytes();
            totalRxBytes += old.getRxBytes();
            old.close();
        }
    }

    private synchronized void closeClientIfCurrent(Elm327Client expected) {
        if (client != expected) return;
        client = null;
        if (expected != null) {
            totalTxBytes += expected.getTxBytes();
            totalRxBytes += expected.getRxBytes();
            expected.close();
        }
    }

    /** Schließt den Client nur, wenn die Session noch aktuell ist (kein Übergriff auf neue Session). */
    private synchronized void closeClientForSession(int session) {
        if (session != sessionGeneration.get()) return;
        closeClient();
    }

    private synchronized void closeClient() {
        Elm327Client c = client;
        client = null;
        if (c != null) {
            totalTxBytes += c.getTxBytes();
            totalRxBytes += c.getRxBytes();
            c.close();
        }
    }

    private void cancelFuelTrimForConnectionLoss(String reason) {
        if (!fuelTrimTest.isRunning()) return;
        fuelTrimTest.cancel();
        latestValues.clear();
        latestSequences.clear();
        ui.post(() -> {
            fuelTestButton.setText("Fuel-Trim-Test");
            fuelTestStatus.setText("Fuel-Trim-Test: abgebrochen · " + reason);
        });
        append("Fuel-Trim-Test wegen " + reason + " abgebrochen");
    }

    private void updateStats() {
        ui.post(() -> {
            Elm327Client c = client;
            long avg = samples == 0 ? 0 : totalLatency / samples;
            long tx = totalTxBytes + (c == null ? 0 : c.getTxBytes());
            long rx = totalRxBytes + (c == null ? 0 : c.getRxBytes());
            stats.setText(String.format(Locale.GERMANY,
                    "Ø %d ms · Samples %d · Timeouts %d · NO DATA %d · I/O %d · Parser %d · TX/RX %d/%d B · ELM %s",
                    avg, samples, timeouts, noData, ioErrors, parserErrors, tx, rx, adapterVoltage));
        });
    }

    private void showStatus(String s) {
        ui.post(() -> status.setText(s));
    }

    private void append(String s) {
        ui.post(() -> {
            String old = console.getText().toString();
            String next = old + s.replace('\r', ' ').trim() + "\n";
            if (next.length() > 18000) next = next.substring(next.length() - 14000);
            console.setText(next);
        });
    }

    private static String clean(String s) {
        if (s == null) return "–";
        return s.replace('\r', ' ').replace('\n', ' ').trim();
    }

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

    @Override
    protected void onDestroy() {
        sessionGeneration.incrementAndGet();
        monitoring.set(false);
        userDisconnect = true;
        closeClient();
        io.shutdownNow();
        super.onDestroy();
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
