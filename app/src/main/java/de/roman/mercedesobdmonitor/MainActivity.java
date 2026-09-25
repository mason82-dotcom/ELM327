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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int REQ_NET = 41;
    private static final String LOCAL_NET_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    private final AtomicBoolean exclusiveRequest = new AtomicBoolean(false);

    private EditText hostInput;
    private EditText portInput;
    private EditText terminalInput;
    private TextView status;
    private TextView stats;
    private TextView console;
    private LinearLayout pidBox;
    private Button dtcButton;
    private SparklineView latencyGraph;

    private final Map<Integer, TextView> pidRows = new LinkedHashMap<>();
    private final List<ObdPid> activePids = new ArrayList<>();
    private final CsvLogger csv = new CsvLogger();

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
        send.setOnClickListener(v -> sendTerminal());
        export.setOnClickListener(v -> exportCsv());
        clear.setOnClickListener(v -> {
            csv.clear();
            console.setText("Log gelöscht.\n");
            latencyGraph.clear();
            samples = totalLatency = timeouts = noData = ioErrors = parserErrors = totalTxBytes = totalRxBytes = 0;
            pollCycle = 0;
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
        if (!ensurePermissions()) return;
        saveSettings();
        userDisconnect = false;
        io.execute(this::connectAndStart);
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

    private void connectAndStart() {
        disconnect(false);
        final String host = hostInput.getText().toString().trim();
        final int port;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            showStatus("Ungültiger TCP-Port");
            return;
        }

        try {
            showStatus("Verbinde mit " + host + ":" + port + " …");
            long connectMs = connectWithFallback(host, port);
            append("TCP verbunden in " + connectMs + " ms");

            command("ATZ", 3000);
            command("ATE0", 1500);
            command("ATL0", 1500);
            command("ATS0", 1500);
            command("ATH0", 1500);
            command("ATAT1", 1500);
            command("ATSP0", 1500);
            command("ATST64", 1500);

            elmId = clean(command("ATI", 1800).raw);
            adapterVoltage = clean(command("ATRV", 1800).raw);

            detectPids();
            String dp = clean(command("ATDP", 1800).raw);
            String dpn = clean(command("ATDPN", 1800).raw);
            protocol = describeProtocol(dp, dpn);
            showStatus("Verbunden · " + elmId + " · " + protocol);
            append("Adapter: " + elmId);
            append("Protokoll: " + protocol);
            append("Versorgung: " + adapterVoltage);
            monitoring.set(true);
            monitorLoop();
        } catch (Exception e) {
            ioErrors++;
            append("Verbindungsfehler: " + e.getMessage());
            showStatus("Verbindung fehlgeschlagen");
            closeClient();
            updateStats();
        }
    }

    private void detectPids() throws IOException {
        Set<Integer> supported = new java.util.HashSet<>();
        Elm327Client.CommandResult p0 = command("0100", 2500);
        supported.addAll(ObdParser.supportedPids(p0.raw, 0x00));
        if (supported.contains(0x20)) {
            Elm327Client.CommandResult p20 = command("0120", 2500);
            supported.addAll(ObdParser.supportedPids(p20.raw, 0x20));
        }
        if (supported.contains(0x40)) {
            Elm327Client.CommandResult p40 = command("0140", 2500);
            supported.addAll(ObdParser.supportedPids(p40.raw, 0x40));
        }

        activePids.clear();
        for (ObdPid pid : ObdPid.defaultPids()) {
            if (supported.isEmpty() || supported.contains(pid.pid)) activePids.add(pid);
        }
        append("Aktive Live-PIDs: " + activePids.size());
    }

    private void monitorLoop() {
        pollCycle = 0;
        while (monitoring.get() && !userDisconnect) {
            if (exclusiveRequest.get()) {
                sleepQuiet(40);
                continue;
            }

            Elm327Client c = client;
            if (c == null || !c.isConnected()) {
                reconnectAfterFailure();
                continue;
            }

            int cycle = ++pollCycle;
            for (ObdPid pid : new ArrayList<>(activePids)) {
                if (!monitoring.get() || userDisconnect || exclusiveRequest.get()) break;
                if (!shouldPoll(pid.pid, cycle)) continue;

                try {
                    Elm327Client.CommandResult r = command(pid.command(), 1800);
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
                    csv.record(System.currentTimeMillis(), pid, value, r.elapsedMs, r.raw);
                    ui.post(() -> {
                        TextView v = pidRows.get(pid.pid);
                        if (v != null) v.setText(pid.format(value));
                        latencyGraph.addValue(r.elapsedMs);
                        updateStats();
                    });
                } catch (SocketTimeoutException e) {
                    timeouts++;
                    append("Timeout bei " + pid.command());
                    updateStats();
                } catch (IOException e) {
                    ioErrors++;
                    append("I/O: " + e.getMessage());
                    closeClient();
                    updateStats();
                    break;
                }
                sleepQuiet(25);
            }
            sleepQuiet(70);
        }
    }

    private boolean shouldPoll(int pid, int cycle) {
        // Schnelle PIDs: jeder Zyklus. Mittlere: jeder 2. Zyklus. Langsame: jeder 5. Zyklus.
        return switch (pid) {
            case 0x0C, 0x10, 0x06, 0x08, 0x44 -> true;
            case 0x0D, 0x04, 0x11, 0x0B -> (cycle % 2) == 0;
            default -> (cycle % 5) == 0;
        };
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void reconnectAfterFailure() {
        if (userDisconnect || !monitoring.get()) return;
        showStatus("Verbindung unterbrochen · Reconnect …");
        try {
            Thread.sleep(2000);
            String host = hostInput.getText().toString().trim();
            int port = Integer.parseInt(portInput.getText().toString().trim());
            connectWithFallback(host, port);
            command("ATE0", 1500);
            command("ATL0", 1500);
            command("ATS0", 1500);
            command("ATH0", 1500);
            command("ATSP0", 1500);
            showStatus("Wieder verbunden · " + protocol);
            append("Auto-Reconnect erfolgreich");
        } catch (Exception e) {
            ioErrors++;
            closeClient();
            updateStats();
        }
    }

    private Elm327Client.CommandResult command(String cmd, int timeoutMs) throws IOException {
        Elm327Client c = client;
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
                synchronized (c) {
                    sb.append("Generische OBD-II-Fehlercodes\n\n");
                    sb.append(readDtcMode(c, "Gespeichert", "03", 0x43));
                    sleepQuiet(180);
                    sb.append("\n\n").append(readDtcMode(c, "Pending", "07", 0x47));
                    sleepQuiet(180);
                    sb.append("\n\n").append(readDtcMode(c, "Permanent", "0A", 0x4A));
                    sleepQuiet(250);

                    // Nach dem DTC-Scan einen harmlosen Read-Only-Request als Verbindungsprobe.
                    try {
                        c.sendCommand("0100", 2200);
                    } catch (IOException probeError) {
                        append("DTC-Nachtest: Verbindung wird neu aufgebaut – " + probeError.getMessage());
                        closeClient();
                    }
                }
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
                status.setText("Verbunden · " + elmId + " · " + protocol);
                new AlertDialog.Builder(this)
                        .setTitle("OBD-II Fehlercodes")
                        .setMessage(result)
                        .setPositiveButton("OK", null)
                        .show();
            });
        });
    }

    private String readDtcMode(Elm327Client c, String label, String cmd, int responseMode) {
        try {
            Elm327Client.CommandResult r = c.sendCommand(cmd, 3500);
            String raw = clean(r.raw);
            append("DTC " + label + " [" + cmd + "]: " + raw);
            List<String> codes = ObdParser.dtcs(r.raw, responseMode);
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
            return label + ": Timeout";
        } catch (IOException e) {
            ioErrors++;
            return label + ": Kommunikationsfehler – " + e.getMessage();
        }
    }

    private void sendTerminal() {
        if (exclusiveRequest.get()) {
            Toast.makeText(this, "DTC-Scan läuft gerade", Toast.LENGTH_SHORT).show();
            return;
        }
        final String cmd = terminalInput.getText().toString().trim();
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
                append(command(cmd, 4000).raw);
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

    private long connectWithFallback(String host, int port) throws IOException {
        IOException standardRouteError = null;

        // Auf dem OnePlus 13R routet Android die lokale 192.168.0.x-Strecke korrekt über WLAN.
        // Diese Variante vermeidet den auf OxygenOS beobachteten EPERM-Fehler beim Network-SocketFactory-Binding.
        Elm327Client direct = new Elm327Client(host, port, null);
        try {
            long ms = direct.connect(3500);
            client = direct;
            append("Android-Standardroute verwendet");
            return ms;
        } catch (IOException e) {
            direct.close();
            standardRouteError = e;
            append("Standardroute fehlgeschlagen: " + e.getMessage());
        }

        IOException last = standardRouteError;
        for (Network network : findWifiNetworks()) {
            Elm327Client candidate = new Elm327Client(host, port, network);
            try {
                long ms = candidate.connect(3500);
                client = candidate;
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
            userDisconnect = true;
            monitoring.set(false);
            showStatus("Getrennt");
            append("Verbindung getrennt");
        }
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
