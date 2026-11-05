package de.roman.mercedesobdmonitor;

import android.content.Context;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ELM327-Sitzung: Verbindung, Live-Polling, Reconnect und Read-Only-Diagnosen.
 *
 * Prozessweites Singleton, damit Verbindung und CSV-Log unabhängig von der Activity
 * weiterleben; der {@link ObdService} hält den Prozess im Vordergrund, solange
 * die Sitzung aktiv ist. Der Darstellungszustand ({@link State}) und alle
 * Listener-Aufrufe gehören dem Main-Thread; eine neu angebundene Activity
 * zeichnet sich daraus neu. Bewusst ohne gespeicherten Context.
 */
public final class ObdSession {
    public enum Op { DTC, MISFIRE, INSPECTION, MONITOR_TESTS }

    /** UI-Callbacks; alle Aufrufe im Main-Thread. */
    public interface Listener {
        /** Alles neu zeichnen (beim Anbinden und nach „Log leeren“). */
        void render(State state);
        void onStatus(String status);
        void onConsole(String text);
        void onPidValue(int pid, String text);
        void onLatency(long ms);
        void onStats(String text);
        void onPassive(State state);
        void onFuelTest(String button, String status);
        void onBusy(Op op, boolean busy);
        void onDialog(String title, String message);
        void onToast(String message);
    }

    /** Für den Service: Sitzung aktiv (verbunden oder im Reconnect) plus Kurzstatus. Main-Thread. */
    public interface Observer {
        void onSessionChanged(boolean active, String status, String stats);
    }

    /** Darstellungszustand; nur im Main-Thread lesen und schreiben. */
    public static final class State {
        public String status = "Nicht verbunden";
        final StringBuilder console = new StringBuilder("Bereit.\n");
        public final Map<Integer, String> pidTexts = new HashMap<>();
        public final ArrayDeque<Long> latencies = new ArrayDeque<>();
        public String stats = "";
        public String operatingText = "Betriebszustand: –";
        public int operatingColor = Color.LTGRAY;
        public String voltageText = "Spannungsbewertung: –";
        public boolean koeo;
        public String fuelButton = FUEL_BUTTON;
        public String fuelStatus = FUEL_READY;
        public String warmupButton = WARMUP_BUTTON;
        public String warmupStatus = WARMUP_READY;
        /** Monitorstatus der laufenden Fahrt (01 41); null, solange nicht gelesen/unterstützt. */
        public String driveCycleText;
        public final Set<Op> busy = EnumSet.noneOf(Op.class);

        public String console() {
            return console.toString();
        }
    }

    private static final String FUEL_BUTTON = "Fuel-Trim-Test";
    private static final String FUEL_READY = "Fuel-Trim-Test: bereit";
    private static final String WARMUP_BUTTON = "Warmlauf-Check";
    private static final String WARMUP_READY = "Warmlauf-Check: bereit";
    /** 01 41 nur jeden n-ten Polling-Zyklus lesen (ändert sich langsam). */
    private static final int DRIVE_CYCLE_EVERY = 20;
    private static final int CONSOLE_MAX = 18000;
    private static final int CONSOLE_KEEP = 14000;
    /** Entspricht der Breite des SparklineView. */
    private static final int LATENCY_POINTS = 120;
    private static final int MAX_PENDING_DIALOGS = 10;

    private static ObdSession instance;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    private final AtomicBoolean exclusiveRequest = new AtomicBoolean(false);
    private final AtomicInteger sessionGeneration = new AtomicInteger(0);
    private final AtomicLong telemetrySequence = new AtomicLong(0);

    /** Wird beim Verbinden neu befüllt und parallel vom Polling gelesen. */
    private final List<ObdPid> activePids = new CopyOnWriteArrayList<>();
    private final CsvLogger csv = new CsvLogger(this::append);
    private final Map<Integer, Double> latestValues = new ConcurrentHashMap<>();
    private final Map<Integer, Long> latestSequences = new ConcurrentHashMap<>();
    private final FuelTrimTest fuelTrimTest = new FuelTrimTest();
    private final WarmupCheck warmupCheck = new WarmupCheck();
    /** Steuergerät meldet PID 41 (Monitorstatus dieser Fahrt) als unterstützt. */
    private volatile boolean driveCyclePid;

    private volatile Elm327Client client;
    private volatile boolean userDisconnect;
    /** true zwischen „Verbinden“ und „Trennen“/endgültigem Verbindungsfehler (auch im Reconnect). */
    private volatile boolean active;
    // Statistik: geschrieben von I/O-Threads, gelesen/zurückgesetzt im Main-Thread.
    private final AtomicLong samples = new AtomicLong();
    private final AtomicLong totalLatency = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicLong noData = new AtomicLong();
    private final AtomicLong ioErrors = new AtomicLong();
    private final AtomicLong parserErrors = new AtomicLong();
    private final AtomicLong totalTxBytes = new AtomicLong();
    private final AtomicLong totalRxBytes = new AtomicLong();
    // Geschrieben im I/O-Thread, gelesen im Main-Thread.
    private volatile String elmId = "–";
    private volatile String protocol = "–";
    /** null = unbekannt (Parser erkennt automatisch), sonst CAN/Legacy laut ATDPN. */
    private volatile Boolean protocolIsCan = null;
    private volatile ConnectivityManager connectivity;
    /** ATDPN-Rohwert der Erstverbindung; beim Reconnect wird das Protokoll fest gesetzt. */
    private volatile String protocolNumber = null;
    private final LinkPolicy.TimeoutTracker pollTimeouts = new LinkPolicy.TimeoutTracker();
    /** Erste Fahrzeuganfrage nach Reconnect bekommt längeren Timeout (Protokollsuche). */
    private volatile boolean firstRequestAfterInit = false;
    private volatile String adapterVoltage = "–";

    // Nur Main-Thread.
    private final State state = new State();
    private final List<String[]> pendingDialogs = new ArrayList<>();
    private Listener listener;
    private Observer observer;

    private ObdSession() { }

    public static synchronized ObdSession get() {
        if (instance == null) instance = new ObdSession();
        return instance;
    }

    // ---------------------------------------------------------------- Anbindung (Main-Thread)

    /** UI anbinden (zeichnet sofort neu und zeigt zwischenzeitliche Dialoge) oder mit null lösen. */
    public void setUiListener(Listener l) {
        listener = l;
        if (l == null) return;
        l.render(state);
        for (String[] d : pendingDialogs) l.onDialog(d[0], d[1]);
        pendingDialogs.clear();
    }

    /** Löst die UI nur, wenn sie noch die angebundene ist (Neuerzeugung der Activity). */
    public void clearUiListener(Listener l) {
        if (listener == l) listener = null;
    }

    public void setObserver(Observer o) {
        observer = o;
    }

    public boolean isActive() {
        return active;
    }

    // ---------------------------------------------------------------- Verbindung

    /**
     * Startet eine neue Sitzung. Host/Port kommen als unveränderliche Werte aus dem UI-Thread.
     * @return false, wenn gerade eine Diagnose läuft und nicht verbunden wurde
     */
    public boolean connect(Context context, String host, int port) {
        if (exclusiveRequest.get()) {
            toast("Diagnosevorgang läuft gerade");
            return false;
        }
        // Application-Context: ConnectivityManager hielt auf älteren Android-Versionen die Activity fest.
        connectivity = context.getApplicationContext().getSystemService(ConnectivityManager.class);
        csv.setDirectory(CsvLogger.logDirectory(context.getApplicationContext()));
        final int session = sessionGeneration.incrementAndGet();
        userDisconnect = false;
        monitoring.set(false);
        latestValues.clear();
        latestSequences.clear();
        fuelTrimTest.cancel();
        closeClient();
        state.driveCycleText = null;
        if (listener != null) listener.onPassive(state);
        // Status vor „aktiv“ setzen, damit die Benachrichtigung nicht kurz „Getrennt“ zeigt.
        showStatus("Verbinde mit " + host + ":" + port + " …");
        setActive(true);

        io.execute(() -> connectAndStart(session, host, port));
        return true;
    }

    /** Vom Benutzer ausgelöstes Trennen (App oder Benachrichtigung). */
    public void disconnect() {
        disconnect(null);
    }

    /** @param reason optionaler Grund für die Konsole */
    public void disconnect(String reason) {
        sessionGeneration.incrementAndGet();
        userDisconnect = true;
        monitoring.set(false);
        latestValues.clear();
        latestSequences.clear();
        fuelTrimTest.cancel();
        setFuel(FUEL_BUTTON, FUEL_READY);
        if (warmupCheck.isRunning()) finishWarmupCheck();
        showStatus("Getrennt");
        if (reason != null) append(reason);
        append("Verbindung getrennt");
        closeClient();
        csv.flush();
        setActive(false);
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
            ioErrors.incrementAndGet();
            append("Verbindungsfehler: " + e.getMessage());
            showStatus("Verbindung fehlgeschlagen");
            closeClient();
            updateStats();
            setActive(false);
        }
    }

    private void detectPids(int session) throws IOException {
        Set<Integer> supported = new HashSet<>();
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

        driveCyclePid = supported.contains(0x41);
        activePids.clear();
        for (ObdPid pid : ObdPid.defaultPids()) {
            if (supported.isEmpty() || supported.contains(pid.pid)) activePids.add(pid);
        }
        append("Aktive Live-PIDs: " + activePids.size());
    }

    private void monitorLoop(int session, String host, int port) {
        int pollCycle = 0;
        while (monitoring.get() && !userDisconnect && session == sessionGeneration.get()) {
            if (exclusiveRequest.get()) {
                sleepQuiet(40);
                continue;
            }

            Elm327Client c = client;
            if (c == null || !c.isConnected()) {
                // Während des Reconnects kommen keine Messwerte, die den Puffer leeren würden.
                csv.flush();
                reconnectAfterFailure(session, host, port);
                continue;
            }

            int cycle = ++pollCycle;
            for (ObdPid pid : activePids) {
                if (!monitoring.get() || userDisconnect || exclusiveRequest.get()
                        || session != sessionGeneration.get()) break;
                if (!PollSchedule.shouldPoll(pid.pid, cycle, fuelTrimTest.isRunning())) continue;

                try {
                    int timeoutMs = firstRequestAfterInit ? LinkPolicy.FIRST_REQUEST_TIMEOUT_MS : 1800;
                    Elm327Client.CommandResult r = command(session, pid.command(), timeoutMs);
                    firstRequestAfterInit = false;
                    pollTimeouts.onResponse();
                    if (ObdParser.isLinkError(r.raw)) {
                        ioErrors.incrementAndGet();
                        continue;
                    }
                    if (ObdParser.isNoData(r.raw)) {
                        noData.incrementAndGet();
                        continue;
                    }
                    Double value = pid.parse(r.raw);
                    if (value == null) {
                        parserErrors.incrementAndGet();
                        continue;
                    }
                    samples.incrementAndGet();
                    totalLatency.addAndGet(r.elapsedMs);
                    if (session != sessionGeneration.get()) break;
                    latestValues.put(pid.pid, value);
                    latestSequences.put(pid.pid, telemetrySequence.incrementAndGet());
                    csv.record(System.currentTimeMillis(), pid, value, r.elapsedMs, r.raw);
                    final String text = pid.format(value);
                    final long latency = r.elapsedMs;
                    ui.post(() -> {
                        state.pidTexts.put(pid.pid, text);
                        if (state.latencies.size() >= LATENCY_POINTS) state.latencies.removeFirst();
                        state.latencies.addLast(latency);
                        if (listener != null) {
                            listener.onPidValue(pid.pid, text);
                            listener.onLatency(latency);
                        }
                    });
                    updateStats();
                } catch (SocketTimeoutException e) {
                    timeouts.incrementAndGet();
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
                    // Nach „Trennen“/neuer Session ist der Socketabbruch erwartet, kein Fehler.
                    if (userDisconnect || session != sessionGeneration.get()) break;
                    ioErrors.incrementAndGet();
                    append("I/O: " + e.getMessage());
                    cancelFuelTrimForConnectionLoss("Verbindungsfehler");
                    closeClientIfCurrent(c);
                    updateStats();
                    break;
                }
                sleepQuiet(25);
            }
            if (driveCyclePid && cycle % DRIVE_CYCLE_EVERY == 0 && monitoring.get() && !userDisconnect
                    && !exclusiveRequest.get() && session == sessionGeneration.get()) {
                Elm327Client current = client;
                if (current != null && current.isConnected()) pollDriveCycle(session, current);
            }
            updatePassiveDiagnostics();
            sleepQuiet(70);
        }
    }

    /** 01 41 im langsamen Takt: welche Monitore in dieser Fahrt schon abgeschlossen sind. */
    private void pollDriveCycle(int session, Elm327Client c) {
        try {
            Elm327Client.CommandResult r = command(session, "0141", 1800);
            pollTimeouts.onResponse();
            Readiness cycle = Readiness.parseDriveCycle(r.raw);
            if (cycle == null || cycle.monitors.isEmpty()) return;
            final String text = "Fahrzyklus (01 41): " + cycle.summary();
            ui.post(() -> {
                state.driveCycleText = text;
                if (listener != null) listener.onPassive(state);
            });
        } catch (SocketTimeoutException e) {
            timeouts.incrementAndGet();
            boolean resynced = c.resync(1500);
            if (pollTimeouts.onTimeout(resynced)) {
                append("Timeout bei 0141 · Verbindung wird neu aufgebaut");
                pollTimeouts.reset();
                cancelFuelTrimForConnectionLoss("Timeout");
                closeClientIfCurrent(c);
            }
            updateStats();
        } catch (IOException e) {
            if (userDisconnect || session != sessionGeneration.get()) return;
            ioErrors.incrementAndGet();
            append("I/O: " + e.getMessage());
            cancelFuelTrimForConnectionLoss("Verbindungsfehler");
            closeClientIfCurrent(c);
            updateStats();
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
            ioErrors.incrementAndGet();
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

    // ---------------------------------------------------------------- Diagnosen

    public void readDtcs() {
        Elm327Client c = client;
        if (c == null || !c.isConnected()) {
            append("DTC: nicht verbunden");
            toast("Keine ELM327-Verbindung");
            return;
        }
        if (fuelTrimTest.isRunning()) {
            toast("Fuel-Trim-Test läuft – bitte zuerst beenden");
            return;
        }
        if (!exclusiveRequest.compareAndSet(false, true)) {
            toast("Diagnosevorgang läuft bereits");
            return;
        }

        setBusy(Op.DTC, true);
        showStatus("DTC-Scan läuft …");

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
                    if (c.isConnected()) {
                        try {
                            c.sendCommand("0100", 2200);
                        } catch (IOException probeError) {
                            append("DTC-Nachtest: Verbindung wird neu aufgebaut – " + probeError.getMessage());
                            closeClientIfCurrent(c);
                        }
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

            setBusy(Op.DTC, false);
            showStatus(statusAfterDiagnosis("DTC-Scan beendet · Reconnect läuft …"));
            showDialog("OBD-II Fehlercodes", report);
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
        if (!c.isConnected()) {
            // Ein vorheriger Modus hat die Verbindung bereits verworfen (Timeout/I/O).
            return label + ": übersprungen · Verbindung wird neu aufgebaut";
        }
        try {
            Elm327Client.CommandResult r = c.sendCommand(cmd, 3500);
            String raw = clean(r.raw);
            append("DTC " + label + " [" + cmd + "]: " + raw);
            List<String> codes = ObdParser.dtcs(r.raw, responseMode, protocolIsCan);
            if (collected != null) collected.addAll(codes);
            if (codes.isEmpty()) {
                if (raw.isEmpty()) return label + ": keine Antwort";
                if (ObdParser.isLinkError(r.raw)) {
                    return label + ": nicht gelesen – Adapter meldet " + raw;
                }
                if (ObdParser.isNoData(r.raw)) {
                    return responseMode == 0x4A
                            ? label + ": nicht unterstützt / NO DATA"
                            : label + ": keine Fehlercodes gemeldet";
                }
                if (ObdParser.isEmptyDtcResponse(r.raw, responseMode, protocolIsCan)) {
                    return responseMode == 0x4A
                            ? label + ": keine permanenten Fehlercodes"
                            : label + ": keine Fehlercodes gemeldet";
                }
                return label + ": keine Fehlercodes erkannt\nRohantwort: " + raw;
            }
            StringBuilder sb = new StringBuilder(label).append(":");
            for (String code : codes) {
                sb.append("\n• ").append(DtcDescriptions.describe(code));
            }
            return sb.toString();
        } catch (SocketTimeoutException e) {
            timeouts.incrementAndGet();
            // Wie beim Live-Polling: auf den verspäteten Prompt warten; neu verbinden
            // nur, wenn der Adapter nicht wieder synchron wird.
            if (c.resync(1500)) {
                append("DTC " + label + ": Timeout · Prompt resynchronisiert, Verbindung bleibt");
                return label + ": Timeout – nicht gelesen";
            }
            append("DTC " + label + ": Timeout · kein Prompt, Verbindung wird neu aufgebaut");
            closeClientIfCurrent(c);
            return label + ": Timeout · Verbindung wird neu aufgebaut";
        } catch (IOException e) {
            ioErrors.incrementAndGet();
            closeClientIfCurrent(c);
            return label + ": Kommunikationsfehler – " + e.getMessage();
        }
    }

    public void runMisfireAnalysis() {
        runExclusiveDiagnosis(Op.MISFIRE, "Aussetzer-Analyse", this::readMisfires);
    }

    public void runMonitorTests() {
        runExclusiveDiagnosis(Op.MONITOR_TESTS, "Monitortests (Mode 06)", this::readMonitorTests);
    }

    public void runInspectionCheck() {
        runExclusiveDiagnosis(Op.INSPECTION, "HU/AU-Vorab-Check", this::readInspection);
    }

    private interface Diagnosis {
        String run(Elm327Client c) throws IOException;
    }

    /**
     * Führt eine Read-Only-Diagnose mit exklusivem Adapterzugriff aus (Live-Polling pausiert)
     * und zeigt das Ergebnis als Dialog. Timeouts werden wie beim DTC-Scan behandelt.
     */
    private void runExclusiveDiagnosis(Op op, String title, Diagnosis job) {
        Elm327Client c = client;
        if (c == null || !c.isConnected()) {
            toast("Keine ELM327-Verbindung");
            return;
        }
        if (fuelTrimTest.isRunning()) {
            toast("Fuel-Trim-Test läuft – bitte zuerst beenden");
            return;
        }
        if (!exclusiveRequest.compareAndSet(false, true)) {
            toast("Diagnosevorgang läuft bereits");
            return;
        }
        setBusy(op, true);
        showStatus(title + " läuft …");

        io.execute(() -> {
            String report;
            try {
                synchronized (c) {
                    report = job.run(c);
                }
                append(title + " abgeschlossen");
            } catch (SocketTimeoutException e) {
                timeouts.incrementAndGet();
                append(title + ": Timeout · Verbindung wird neu synchronisiert");
                closeClientIfCurrent(c);
                report = title + " abgebrochen: Timeout.\nDie Verbindung wird neu aufgebaut.";
            } catch (IOException e) {
                ioErrors.incrementAndGet();
                closeClientIfCurrent(c);
                report = title + " fehlgeschlagen:\n" + e.getMessage();
            } catch (RuntimeException e) {
                parserErrors.incrementAndGet();
                report = title + " fehlgeschlagen (Auswertung):\n" + e;
            } finally {
                exclusiveRequest.set(false);
            }
            setBusy(op, false);
            showStatus(statusAfterDiagnosis(title + " beendet · Reconnect läuft …"));
            updateStats();
            showDialog(title, report);
        });
    }

    private String statusAfterDiagnosis(String reconnecting) {
        Elm327Client current = client;
        return current != null && current.isConnected()
                ? "Verbunden · " + elmId + " · " + protocol
                : reconnecting;
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

    /** Mode 06: alle Monitortests mit Grenzwerten. Einzelne Timeouts werden übersprungen. */
    private String readMonitorTests(Elm327Client c) throws IOException {
        if (Boolean.FALSE.equals(protocolIsCan)) {
            return "Mode 06 wird nur für CAN (ISO 15765-4) ausgewertet. Erkanntes Protokoll: " + protocol;
        }
        try {
            return Mode06Overview.report(Mode06Overview.read(new InspectionReader.Transport() {
                @Override
                public String send(String cmd, int timeoutMs) throws IOException {
                    return diagCommand(c, cmd, timeoutMs).raw;
                }

                @Override
                public boolean resync() {
                    timeouts.incrementAndGet();
                    boolean ok = c.resync(1500);
                    append("Monitortests: Timeout · " + (ok ? "Prompt resynchronisiert, weiter" : "kein Prompt"));
                    return ok;
                }
            }));
        } catch (SocketTimeoutException e) {
            // Der abbrechende Timeout wurde schon in resync() gezählt; runExclusiveDiagnosis zählt ihn erneut.
            timeouts.decrementAndGet();
            throw e;
        }
    }

    /**
     * HU/AU-Vorab-Check: 01 01, 03/07/0A, 01 21/30/31, Freeze Frame, 01 41, 09 02/04/06/08.
     * Einzelne Timeouts werden per Resync überbrückt (siehe InspectionReader).
     */
    private String readInspection(Elm327Client c) throws IOException {
        InspectionReader reader = new InspectionReader(new InspectionReader.Transport() {
            @Override
            public String send(String cmd, int timeoutMs) throws IOException {
                return diagCommand(c, cmd, timeoutMs).raw;
            }

            @Override
            public boolean resync() {
                boolean ok = c.resync(1500);
                append("HU/AU-Check: Timeout · " + (ok ? "Prompt resynchronisiert, weiter"
                        : "kein Prompt"));
                return ok;
            }
        }, protocolIsCan);
        try {
            String report = InspectionCheck.report(reader.read(
                    pid -> activePids.isEmpty() || containsPid(pid)));
            timeouts.addAndGet(reader.timeoutCount());
            return report;
        } catch (SocketTimeoutException e) {
            // Der abbrechende Timeout wird in runExclusiveDiagnosis gezählt.
            timeouts.addAndGet(Math.max(0, reader.timeoutCount() - 1));
            throw e;
        }
    }

    private boolean containsPid(int pid) {
        for (ObdPid p : activePids) if (p.pid == pid) return true;
        return false;
    }

    // ---------------------------------------------------------------- Fuel-Trim-Test

    public void toggleFuelTrimTest() {
        if (fuelTrimTest.isRunning()) {
            fuelTrimTest.cancel();
            setFuel(FUEL_BUTTON, "Fuel-Trim-Test: abgebrochen");
            append("Fuel-Trim-Test abgebrochen");
            return;
        }

        Elm327Client c = client;
        if (c == null || !c.isConnected()) {
            toast("Keine ELM327-Verbindung");
            return;
        }

        Double rpm = latestValues.get(0x0C);
        if (rpm == null || rpm < 300.0) {
            showDialog("Fuel-Trim-Test", "Der Motor muss laufen. Bitte Motor starten und anschließend den Test erneut beginnen.");
            return;
        }

        if (latestValues.get(0x06) == null || latestValues.get(0x07) == null
                || latestValues.get(0x08) == null || latestValues.get(0x09) == null) {
            showDialog("Fuel-Trim-Test", "STFT/LTFT für beide Bänke sind noch nicht vollständig verfügbar. "
                    + "Bitte die Live-Daten kurz weiterlaufen lassen und den Test erneut starten.");
            return;
        }

        Double coolant = latestValues.get(0x05);
        if (coolant == null || coolant < 80.0) {
            String value = coolant == null ? "unbekannt" : String.format(Locale.GERMANY, "%.0f °C", coolant);
            showDialog("Motor noch nicht warm", "Kühlmittel aktuell: " + value
                    + "\n\nFür einen aussagekräftigen Fuel-Trim-Test werden mindestens etwa 80 °C empfohlen. "
                    + "Der Test wurde noch nicht gestartet.");
            return;
        }

        fuelTrimTest.start();
        setFuel("Test abbrechen", "Fuel-Trim-Test: Leerlauf stabilisieren …");
        append("Passiver Fuel-Trim-Test gestartet");
        showDialog("Fuel-Trim-Test gestartet", "1. Fahrzeug stehen lassen und stabilen Leerlauf halten.\n"
                + "2. Die App misst automatisch 20 Sekunden.\n"
                + "3. Danach wirst du aufgefordert, die Drehzahl manuell bei ca. 2500 U/min zu halten.\n\n"
                + "Die App steuert keinerlei Stellglieder und verändert keine Fahrzeugkonfiguration.");
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
        FuelTrimTest.Stage stage = fuelTrimTest.getStage();
        final boolean showTest = fuelTrimTest.isRunning()
                || stage == FuelTrimTest.Stage.DONE || stage == FuelTrimTest.Stage.FAILED;
        final String warmupText = warmupCheck.tick(latestValues, SystemClock.elapsedRealtime());

        ui.post(() -> {
            state.operatingText = stateText;
            state.operatingColor = stateColor;
            state.voltageText = voltageText;
            state.koeo = koeo;
            if (warmupText != null && warmupCheck.isRunning()) state.warmupStatus = warmupText;

            if (koeo) {
                state.fuelStatus = "Fuel-Trim-Test: Motor aus · STFT/Soll-Lambda derzeit nicht bewerten";
            } else if (showTest) {
                state.fuelStatus = "Fuel-Trim-Test: " + testUpdate.status;
            } else {
                state.fuelStatus = FUEL_READY;
            }
            if (testUpdate.failed) {
                state.fuelButton = FUEL_BUTTON;
                state.fuelStatus = "Fuel-Trim-Test: " + testUpdate.status;
            }
            if (testUpdate.completed && testUpdate.report != null) {
                state.fuelButton = FUEL_BUTTON;
                state.fuelStatus = "Fuel-Trim-Test: abgeschlossen";
            }
            if (listener != null) {
                listener.onPassive(state);
                listener.onFuelTest(state.fuelButton, state.fuelStatus);
            }
        });

        if (testUpdate.prompt2500) {
            showDialog("Leerlaufmessung abgeschlossen", "Jetzt die Motordrehzahl manuell auf etwa 2300–2700 U/min anheben und konstant halten. "
                    + "Die zweite Messphase startet automatisch, sobald die Drehzahl stabil ist.");
        }
        if (testUpdate.failed) append("Fuel-Trim-Test " + testUpdate.status);
        if (testUpdate.completed && testUpdate.report != null) {
            append("Fuel-Trim-Test abgeschlossen");
            showDialog("Fuel-Trim-Auswertung", testUpdate.report);
        }
    }

    // ---------------------------------------------------------------- Warmlauf-Check

    /** Startet den passiven Warmlauf-Check oder beendet ihn mit Bericht. Main-Thread. */
    public void toggleWarmupCheck() {
        if (warmupCheck.isRunning()) {
            finishWarmupCheck();
            return;
        }
        Elm327Client c = client;
        if (c == null || !c.isConnected()) {
            toast("Keine ELM327-Verbindung");
            return;
        }
        if (!activePids.isEmpty() && !containsPid(0x05)) {
            showDialog("Warmlauf-Check", "Das Steuergerät meldet keine Kühlmitteltemperatur (PID 05).");
            return;
        }
        warmupCheck.start(SystemClock.elapsedRealtime());
        state.warmupButton = "Warmlauf-Check beenden";
        state.warmupStatus = "Warmlauf: Start …";
        if (listener != null) listener.onPassive(state);
        append("Warmlauf-Check gestartet");
        showDialog("Warmlauf-Check gestartet", "Am aussagekräftigsten nach einem Kaltstart "
                + "(Kühlmittel unter 50 °C): normal losfahren, nach dem Warmlauf mindestens 5 Minuten "
                + "über 60 km/h. Die App wertet nur die ohnehin gelesenen Livewerte aus und läuft auch bei "
                + "ausgeschaltetem Bildschirm weiter.\n\nZum Auswerten „Warmlauf-Check beenden“ tippen.");
    }

    private void finishWarmupCheck() {
        String report = warmupCheck.finish(SystemClock.elapsedRealtime());
        state.warmupButton = WARMUP_BUTTON;
        state.warmupStatus = "Warmlauf-Check: ausgewertet";
        if (listener != null) listener.onPassive(state);
        if (report != null) {
            append("Warmlauf-Check beendet");
            showDialog("Warmlauf-Auswertung", report);
        }
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

    private void cancelFuelTrimForConnectionLoss(String reason) {
        if (!fuelTrimTest.isRunning()) return;
        fuelTrimTest.cancel();
        latestValues.clear();
        latestSequences.clear();
        setFuel(FUEL_BUTTON, "Fuel-Trim-Test: abgebrochen · " + reason);
        append("Fuel-Trim-Test wegen " + reason + " abgebrochen");
    }

    // ---------------------------------------------------------------- Terminal, Log

    public void sendTerminal(String input) {
        if (exclusiveRequest.get()) {
            toast("Diagnosevorgang läuft gerade");
            return;
        }
        final String cmd = input == null ? "" : input.trim();
        final int session = sessionGeneration.get();
        if (cmd.isEmpty()) return;
        if (!CommandSafety.isAllowed(cmd)) {
            String reason = CommandSafety.blockedReason(cmd);
            append("BLOCKIERT > " + cmd + " · " + reason);
            toast("Read-Only-Schutz: " + reason);
            return;
        }
        // Formatändernde Befehle (ATZ, ATH1, …) laufen exklusiv; danach wird der
        // Polling-Zustand wiederhergestellt, damit Livewerte/DTC-Parser weiter stimmen.
        final List<String> restore = LinkPolicy.restoreAfterTerminal(cmd, protocolNumber);
        final boolean exclusive = !restore.isEmpty();
        if (exclusive && !exclusiveRequest.compareAndSet(false, true)) {
            toast("Diagnosevorgang läuft gerade");
            return;
        }
        io.execute(() -> {
            try {
                append("> " + cmd);
                append(command(session, cmd, 4000).raw);
                for (String r : restore) command(session, r, 1500);
                if (exclusive) {
                    if (CommandSafety.normalize(cmd).equals("ATZ")) firstRequestAfterInit = true;
                    append("Adapter-Format für Live-Polling wiederhergestellt: " + String.join(" ", restore));
                }
            } catch (IOException e) {
                append("Terminalfehler: " + e.getMessage());
            } finally {
                if (exclusive) exclusiveRequest.set(false);
            }
        });
    }

    public void exportCsv(Context context) {
        final Context app = context.getApplicationContext();
        io.execute(() -> {
            try {
                String where = csv.export(app);
                toast("CSV exportiert: " + where);
            } catch (IOException e) {
                toast("CSV-Export fehlgeschlagen: " + e.getMessage());
            }
        });
    }

    /**
     * „Neues Log“: aktuelle CSV-Datei abschließen (sie bleibt im App-Speicher), Konsole,
     * Antwortzeiten und Zähler zurücksetzen. Main-Thread.
     */
    public void startNewLog() {
        csv.startNew();
        for (AtomicLong counter : new AtomicLong[] {samples, totalLatency, timeouts, noData,
                ioErrors, parserErrors, totalTxBytes, totalRxBytes}) counter.set(0);
        latestValues.clear();
        latestSequences.clear();
        fuelTrimTest.cancel();
        state.console.setLength(0);
        state.console.append("Neues Log begonnen. Die bisherige CSV-Datei bleibt im App-Speicher erhalten.\n");
        state.latencies.clear();
        state.fuelButton = FUEL_BUTTON;
        state.fuelStatus = FUEL_READY;
        state.stats = statsText();
        if (listener != null) listener.render(state);
        notifyObserver();
    }

    // ---------------------------------------------------------------- Netzwerk

    private long connectWithFallback(String host, int port, int session) throws IOException {
        IOException standardRouteError;

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

    // getAllNetworks() ist ab API 31 deprecated, bleibt aber der einzige synchrone Weg,
    // alle WLAN-Netze ohne NetworkCallback-Registrierung aufzuzählen.
    @SuppressWarnings("deprecation")
    private List<Network> findWifiNetworks() {
        List<Network> out = new ArrayList<>();
        ConnectivityManager cm = connectivity;
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

    private synchronized void installClientForSession(Elm327Client candidate, int session) throws IOException {
        if (session != sessionGeneration.get()) {
            candidate.close();
            throw new IOException("Verbindungsversuch durch neuere Session ersetzt");
        }
        Elm327Client old = client;
        client = candidate;
        if (old != null && old != candidate) {
            totalTxBytes.addAndGet(old.getTxBytes());
            totalRxBytes.addAndGet(old.getRxBytes());
            old.close();
        }
    }

    private synchronized void closeClientIfCurrent(Elm327Client expected) {
        if (client != expected) return;
        client = null;
        if (expected != null) {
            totalTxBytes.addAndGet(expected.getTxBytes());
            totalRxBytes.addAndGet(expected.getRxBytes());
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
            totalTxBytes.addAndGet(c.getTxBytes());
            totalRxBytes.addAndGet(c.getRxBytes());
            c.close();
        }
    }

    // ---------------------------------------------------------------- Main-Thread-Ausgabe

    private String statsText() {
        Elm327Client c = client;
        long n = samples.get();
        long avg = n == 0 ? 0 : totalLatency.get() / n;
        long tx = totalTxBytes.get() + (c == null ? 0 : c.getTxBytes());
        long rx = totalRxBytes.get() + (c == null ? 0 : c.getRxBytes());
        return String.format(Locale.GERMANY,
                "Ø %d ms · Samples %d · Timeouts %d · NO DATA %d · I/O %d · Parser %d · TX/RX %d/%d B · ELM %s",
                avg, n, timeouts.get(), noData.get(), ioErrors.get(), parserErrors.get(), tx, rx, adapterVoltage);
    }

    private void updateStats() {
        ui.post(() -> {
            state.stats = statsText();
            if (listener != null) listener.onStats(state.stats);
            notifyObserver();
        });
    }

    private void showStatus(String s) {
        ui.post(() -> {
            state.status = s;
            if (listener != null) listener.onStatus(s);
            notifyObserver();
        });
    }

    private void append(String s) {
        final String line = s == null ? "null" : s.replace('\r', ' ').trim();
        ui.post(() -> {
            state.console.append(line).append('\n');
            if (state.console.length() > CONSOLE_MAX) {
                state.console.delete(0, state.console.length() - CONSOLE_KEEP);
            }
            if (listener != null) listener.onConsole(state.console());
        });
    }

    private void setFuel(String button, String status) {
        ui.post(() -> {
            state.fuelButton = button;
            state.fuelStatus = status;
            if (listener != null) listener.onFuelTest(button, status);
        });
    }

    private void setBusy(Op op, boolean busy) {
        ui.post(() -> {
            if (busy) state.busy.add(op);
            else state.busy.remove(op);
            if (listener != null) listener.onBusy(op, busy);
        });
    }

    private void setActive(boolean value) {
        active = value;
        ui.post(this::notifyObserver);
    }

    /** Dialoge, die ohne sichtbare UI eintreffen, werden beim nächsten Anbinden gezeigt. */
    private void showDialog(String title, String message) {
        ui.post(() -> {
            if (listener != null) {
                listener.onDialog(title, message);
            } else if (pendingDialogs.size() < MAX_PENDING_DIALOGS) {
                pendingDialogs.add(new String[] {title, message});
            }
        });
    }

    private void toast(String message) {
        ui.post(() -> {
            if (listener != null) listener.onToast(message);
        });
    }

    private void notifyObserver() {
        if (observer != null) observer.onSessionChanged(active, state.status, state.stats);
    }

    private static String clean(String s) {
        if (s == null) return "–";
        return s.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
