package de.roman.mercedesobdmonitor;

import android.Manifest;
import android.app.Activity;
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

    private EditText hostInput;
    private EditText portInput;
    private EditText terminalInput;
    private TextView status;
    private TextView stats;
    private TextView console;
    private LinearLayout pidBox;
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

        LinearLayout endpoint = row();
        hostInput = edit("192.168.0.10", false);
        portInput = edit("35000", true);
        endpoint.addView(hostInput, new LinearLayout.LayoutParams(0, dp(48), 2f));
        endpoint.addView(portInput, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(endpoint);

        LinearLayout controls = row();
        Button connect = button("Verbinden");
        Button disconnect = button("Trennen");
        Button dtc = button("DTC");
        controls.addView(connect, weight());
        controls.addView(disconnect, weight());
        controls.addView(dtc, weight());
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

        TextView termTitle = text("ELM327-Terminal", 18, Color.WHITE);
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
        dtc.setOnClickListener(v -> readDtcs());
        send.setOnClickListener(v -> sendTerminal());
        export.setOnClickListener(v -> exportCsv());
        clear.setOnClickListener(v -> {
            csv.clear();
            console.setText("Log gelöscht.\n");
            latencyGraph.clear();
            samples = totalLatency = timeouts = noData = ioErrors = parserErrors = 0;
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
            Network wifi = findWifiNetwork();
            client = new Elm327Client(host, port, wifi);
            long connectMs = client.connect(3500);
            append("TCP verbunden in " + connectMs + " ms" + (wifi != null ? " (WLAN gebunden)" : ""));

            command("ATZ", 3000);
            command("ATE0", 1500);
            command("ATL0", 1500);
            command("ATS0", 1500);
            command("ATH0", 1500);
            command("ATAT1", 1500);
            command("ATSP0", 1500);
            command("ATST64", 1500);

            elmId = clean(command("ATI", 1800).raw);
            protocol = clean(command("ATDP", 1800).raw);
            adapterVoltage = clean(command("ATRV", 1800).raw);

            detectPids();
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
        while (monitoring.get() && !userDisconnect) {
            Elm327Client c = client;
            if (c == null || !c.isConnected()) {
                reconnectAfterFailure();
                continue;
            }

            for (ObdPid pid : new ArrayList<>(activePids)) {
                if (!monitoring.get() || userDisconnect) break;
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
            }

            try { Thread.sleep(120); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private void reconnectAfterFailure() {
        if (userDisconnect || !monitoring.get()) return;
        showStatus("Verbindung unterbrochen · Reconnect …");
        try {
            Thread.sleep(2000);
            Network wifi = findWifiNetwork();
            String host = hostInput.getText().toString().trim();
            int port = Integer.parseInt(portInput.getText().toString().trim());
            client = new Elm327Client(host, port, wifi);
            client.connect(3500);
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
        io.execute(() -> {
            if (client == null || !client.isConnected()) {
                append("DTC: nicht verbunden");
                return;
            }
            try {
                appendDtcGroup("Gespeichert", command("03", 3500).raw, 0x43);
                appendDtcGroup("Pending", command("07", 3500).raw, 0x47);
                appendDtcGroup("Permanent", command("0A", 3500).raw, 0x4A);
            } catch (IOException e) {
                append("DTC-Fehler: " + e.getMessage());
            }
        });
    }

    private void appendDtcGroup(String label, String raw, int responseMode) {
        List<String> codes = ObdParser.dtcs(raw, responseMode);
        if (codes.isEmpty()) {
            append(label + ": keine DTCs");
            return;
        }
        append(label + ":");
        for (String code : codes) append("  " + DtcDescriptions.describe(code));
    }

    private void sendTerminal() {
        final String cmd = terminalInput.getText().toString().trim();
        if (cmd.isEmpty()) return;
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

    private Network findWifiNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return network;
        }
        return null;
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

    private void closeClient() {
        Elm327Client c = client;
        client = null;
        if (c != null) c.close();
    }

    private void updateStats() {
        ui.post(() -> {
            Elm327Client c = client;
            long avg = samples == 0 ? 0 : totalLatency / samples;
            long tx = c == null ? 0 : c.getTxBytes();
            long rx = c == null ? 0 : c.getRxBytes();
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
