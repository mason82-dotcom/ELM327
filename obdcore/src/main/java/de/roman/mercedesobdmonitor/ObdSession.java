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
import java.nio.charset.StandardCharsets;
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
    public enum Op { DTC, MISFIRE, INSPECTION, MONITOR_TESTS, PROFILE, ATF, STARTER, CAN_MONITOR, KWP }

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
        /**
         * Ergebnis einer Diagnose ({@code op} hat es erzeugt). Standard: wie ein Dialog, damit bestehende
         * Oberflächen unverändert bleiben; das Dashboard ordnet es damit eindeutig seiner Seite zu.
         */
        default void onResult(Op op, String title, String message) { onDialog(title, message); }
        void onToast(String message);
        /** Neuer gültiger Messwert als Zahl (für Zeiger und Verlaufskurven); Main-Thread. */
        default void onPidSample(int pid, double value) { }
        /**
         * Messwert nicht mehr gültig (NO DATA, Parser-/Linkfehler oder Verbindungsverlust). Der Text
         * steht danach auf {@link #INVALID_TEXT}; ein alter Wert darf nicht weiter angezeigt werden.
         */
        default void onPidInvalid(int pid) { }
    }

    /** Anzeige für einen nicht (mehr) gültigen Messwert ({@link LiveValues#INVALID}). */
    public static final String INVALID_TEXT = LiveValues.INVALID;

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
    /** Herkunft einer laufenden Diagnose; -1 außerhalb eines exklusiven Auftrags. */
    private volatile int activeDiagnosisGeneration = -1;
    private final AtomicLong telemetrySequence = new AtomicLong(0);

    /** Wird beim Verbinden neu befüllt und parallel vom Polling gelesen. */
    private final List<ObdPid> activePids = new CopyOnWriteArrayList<>();
    private final CsvLogger csv = new CsvLogger(this::append);
    private final Map<Integer, Double> latestValues = new ConcurrentHashMap<>();
    private final Map<Integer, Long> latestSequences = new ConcurrentHashMap<>();
    /** Zeitpunkt (elapsedRealtime) des letzten frischen Werts je PID; Grundlage des Alterslimits. */
    private final Map<Integer, Long> latestAt = new ConcurrentHashMap<>();
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
    /** Dialog oder Diagnose-Ergebnis, das ohne angebundene UI eintraf ({@code op} null = Dialog). */
    private static final class Pending {
        final Op op; final String title; final String message;
        Pending(Op op, String title, String message) { this.op = op; this.title = title; this.message = message; }
    }
    private final List<Pending> pendingDialogs = new ArrayList<>();
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
        for (Pending d : pendingDialogs) {
            if (d.op != null) l.onResult(d.op, d.title, d.message);
            else l.onDialog(d.title, d.message);
        }
        pendingDialogs.clear();
    }

    /** Löst die UI nur, wenn sie noch die angebundene ist (Neuerzeugung der Activity). */
    public void clearUiListener(Listener l) {
        if (listener == l) listener = null;
    }

    public void setObserver(Observer o) {
        observer = o;
    }

    /** Erkannter Bus nach ATDPN: true = CAN, false = kein CAN, null = noch unbekannt. */
    public Boolean protocolIsCan() {
        return protocolIsCan;
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
        return start(context, host, port, null);
    }

    /** @param transport Bluetooth-Öffner oder null für WLAN; gilt erst, wenn der Start angenommen ist (Review). */
    private boolean start(Context context, String host, int port, ElmLink.Opener transport) {
        if (exclusiveRequest.get()) {
            toast("Diagnosevorgang läuft gerade");
            return false;
        }
        // Ab hier gehört der Transport zur neuen Sitzung; eine abgelehnte Anfrage ändert den laufenden nicht.
        // Ein noch laufender Aufbau der alten Sitzung (Bluetooth bis 8 s) endet sofort (Review).
        replaceTransport(transport);
        // Application-Context: ConnectivityManager hielt auf älteren Android-Versionen die Activity fest.
        connectivity = context.getApplicationContext().getSystemService(ConnectivityManager.class);
        csv.setDirectory(CsvLogger.logDirectory(context.getApplicationContext()));
        final int session = sessionGeneration.incrementAndGet();
        clearDiagnosisUiForSessionChange();
        userDisconnect = false;
        monitoring.set(false);
        latestValues.clear();
        latestSequences.clear();
        latestAt.clear();
        fuelTrimTest.cancel();
        closeClient();
        state.driveCycleText = null;
        if (listener != null) listener.onPassive(state);
        // Status vor „aktiv“ setzen, damit die Benachrichtigung nicht kurz „Getrennt“ zeigt.
        showStatus("Verbinde mit " + target(host, port, transport) + " …");
        setActive(true);

        io.execute(() -> connectAndStart(session, host, port, transport));
        return true;
    }

    /**
     * ADR-005: Transport der aktuellen Sitzung (null = WLAN), nur um einen laufenden Aufbau beim Trennen oder bei
     * einer neuen Verbindung abzubrechen. Die Abläufe selbst bekommen ihren Transport als Parameter (Review: ein
     * gemeinsames Feld ließ eine ältere Sitzung den Transport der neuen lesen).
     */
    private final java.util.concurrent.atomic.AtomicReference<ElmLink.Opener> activeTransport =
            new java.util.concurrent.atomic.AtomicReference<>();

    private void replaceTransport(ElmLink.Opener next) {
        ElmLink.Opener old = activeTransport.getAndSet(next);
        if (old != null && old != next) old.cancel();
    }

    /**
     * Verbindet über einen gekoppelten Bluetooth-ELM327 (ADR-005). Der Aufrufer hat BLUETOOTH_CONNECT geprüft.
     * @return false, wenn nicht verbunden wurde (Diagnose läuft, Bluetooth aus, Berechtigung fehlt)
     */
    public boolean connectBluetooth(Context context, String address, String name) {
        ElmLink.Opener opener;
        try {
            opener = BluetoothLink.opener(context, address, name);
        } catch (IOException e) {
            toast(e.getMessage());
            return false;
        }
        return start(context, opener.describe(), 0, opener);
    }

    /** Vom Benutzer ausgelöstes Trennen (App oder Benachrichtigung). */
    public void disconnect() {
        disconnect(null);
    }

    /** @param reason optionaler Grund für die Konsole */
    public void disconnect(String reason) {
        sessionGeneration.incrementAndGet();
        userDisconnect = true;
        clearDiagnosisUiForSessionChange();
        monitoring.set(false);
        protocolIsCan = null;
        latestValues.clear();
        latestSequences.clear();
        latestAt.clear();
        fuelTrimTest.cancel();
        setFuel(FUEL_BUTTON, FUEL_READY);
        if (warmupCheck.isRunning()) finishWarmupCheck();
        showStatus("Getrennt");
        invalidateAllLive();
        if (reason != null) append(reason);
        append("Verbindung getrennt");
        replaceTransport(null);   // laufenden Bluetooth-Aufbau abbrechen (Review)
        closeClient();
        csv.flush();
        setActive(false);
    }

    private void connectAndStart(int session, String host, int port, ElmLink.Opener transport) {
        try {
            if (session != sessionGeneration.get()) return;
            // Protocol of a previous adapter/car must not be shown or used for this connection.
            protocolIsCan = null;
            showStatus("Verbinde mit " + target(host, port, transport) + " …");
            long connectMs = connectWithFallback(host, port, session, transport);
            append((transport != null ? transport.describe() : "TCP") + " verbunden in " + connectMs + " ms");

            command(session, "ATZ", 3000);
            command(session, "ATE0", 1500);
            command(session, "ATL0", 1500);
            command(session, "ATS0", 1500);
            command(session, "ATH0", 1500);
            command(session, "ATAT1", 1500);
            command(session, "ATSP0", 1500);
            command(session, "ATST64", 1500);

            elmId = ElmText.clean(command(session, "ATI", 1800).raw);
            adapterVoltage = ElmText.clean(command(session, "ATRV", 1800).raw);

            detectPids(session);
            String dp = ElmText.clean(command(session, "ATDP", 1800).raw);
            String dpn = ElmText.clean(command(session, "ATDPN", 1800).raw);
            protocol = describeProtocol(dp, dpn);
            protocolIsCan = ObdParser.isCanProtocol(dpn);
            protocolNumber = dpn;
            // 11-bit-CAN: headers on, so the engine's answer (7E8) wins over the transmission's (7E9).
            String headers = LinkPolicy.liveHeaders(dpn);
            if (!"ATH0".equals(headers)) {
                command(session, headers, 1500);
                append("Live-Polling mit CAN-Header: Werte vom Motorsteuergerät (7E8) bevorzugt");
            }
            pollTimeouts.reset();
            showStatus("Verbunden · " + elmId + " · " + protocol);
            append("Adapter: " + elmId);
            append("Protokoll: " + protocol);
            append("Versorgung: " + adapterVoltage);
            if (session != sessionGeneration.get()) return;
            monitoring.set(true);
            monitorLoop(session, host, port, transport);
        } catch (Exception e) {
            if (session != sessionGeneration.get()) return;
            ioErrors.incrementAndGet();
            append("Verbindungsfehler: " + e.getMessage());
            showStatus("Verbindung fehlgeschlagen");
            invalidateAllLive();
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
            if (supported.isEmpty() ? ObdPid.isCore(pid.pid) : supported.contains(pid.pid)) activePids.add(pid);
        }
        append("Aktive Live-PIDs: " + activePids.size());
    }

    /**
     * Einen Messwert ungültig machen: kein alter Wert bleibt in Anzeige, Diagnosen oder Grafiken stehen.
     * Nur für die laufende Sitzung – ein abgelöster Poll-Thread darf den frischen Wert einer neuen Sitzung
     * nicht löschen (Review von #6).
     */
    private void invalidatePid(int session, int pid) {
        if (session != sessionGeneration.get()) return;
        latestValues.remove(pid);
        latestSequences.remove(pid);
        latestAt.remove(pid);
        ui.post(() -> {
            if (session == sessionGeneration.get()) markInvalid(pid);
        });
    }

    /** Alle Live-Werte ungültig (Verbindungsverlust, Trennen, Verbindungsfehler). */
    private void invalidateAllLive() {
        latestValues.clear();
        latestSequences.clear();
        latestAt.clear();
        ui.post(() -> {
            for (Integer pid : LiveValues.markAllInvalid(state.pidTexts)) notifyInvalid(pid);
        });
    }

    /** Main-Thread. */
    private void markInvalid(int pid) {
        if (LiveValues.markInvalid(state.pidTexts, pid)) notifyInvalid(pid);
    }

    /** Main-Thread. */
    private void notifyInvalid(int pid) {
        if (listener != null) {
            listener.onPidValue(pid, INVALID_TEXT);
            listener.onPidInvalid(pid);
        }
    }

    private void monitorLoop(int session, String host, int port, ElmLink.Opener transport) {
        int pollCycle = 0;
        while (monitoring.get() && !userDisconnect && session == sessionGeneration.get()) {
            // Age limit on every path (slow PIDs, paused polling during diagnoses, any future branch).
            for (Integer old : LiveValues.expired(latestAt, SystemClock.elapsedRealtime(), LiveValues.MAX_AGE_MS)) {
                invalidatePid(session, old);
            }
            if (exclusiveRequest.get()) {
                ElmText.sleepQuiet(40);
                continue;
            }

            Elm327Client c = client;
            if (c == null || !c.isConnected()) {
                // Während des Reconnects kommen keine Messwerte, die den Puffer leeren würden.
                csv.flush();
                invalidateAllLive();
                reconnectAfterFailure(session, host, port, transport);
                continue;
            }

            int cycle = ++pollCycle;
            for (ObdPid pid : activePids) {
                if (!monitoring.get() || userDisconnect || exclusiveRequest.get()
                        || session != sessionGeneration.get()) break;
                if (!PollSchedule.shouldPoll(pid.pid, cycle, fuelTrimTest.isRunning())) continue;

                // Freshness guard: a polled PID keeps a value only if THIS poll produced one. Every other way
                // out of the try block (NO DATA, link/parser error, timeout, I/O error, session change, a future
                // new branch) leaves it at "–" – the timeout branch once forgot this (review of #4).
                boolean fresh = false;
                try {
                    int timeoutMs = firstRequestAfterInit ? LinkPolicy.FIRST_REQUEST_TIMEOUT_MS : 1800;
                    Elm327Client.CommandResult r = command(session, pid.command(), timeoutMs);
                    firstRequestAfterInit = false;
                    pollTimeouts.onResponse();
                    PollOutcome.Result result = PollOutcome.classify(pid, r.raw);
                    if (!result.outcome.isValue()) {
                        if (result.outcome == PollOutcome.LINK_ERROR) ioErrors.incrementAndGet();
                        else if (result.outcome == PollOutcome.NO_DATA) noData.incrementAndGet();
                        else parserErrors.incrementAndGet();
                        continue;
                    }
                    final double value = result.value;
                    samples.incrementAndGet();
                    totalLatency.addAndGet(r.elapsedMs);
                    if (session != sessionGeneration.get()) break;
                    latestValues.put(pid.pid, value);
                    latestSequences.put(pid.pid, telemetrySequence.incrementAndGet());
                    latestAt.put(pid.pid, SystemClock.elapsedRealtime());
                    csv.record(System.currentTimeMillis(), pid, value, r.elapsedMs, r.raw);
                    final String text = pid.format(value);
                    final long latency = r.elapsedMs;
                    final double sample = value;
                    ui.post(() -> {
                        if (session != sessionGeneration.get()) return;
                        state.pidTexts.put(pid.pid, text);
                        if (state.latencies.size() >= LATENCY_POINTS) state.latencies.removeFirst();
                        state.latencies.addLast(latency);
                        if (listener != null) {
                            listener.onPidValue(pid.pid, text);
                            listener.onPidSample(pid.pid, sample);
                            listener.onLatency(latency);
                        }
                    });
                    fresh = true;
                    updateStats();
                } catch (SocketTimeoutException e) {
                    timeouts.incrementAndGet();
                    firstRequestAfterInit = false;
                    // No answer is not a value: "–" at once, not only after the resync wait below.
                    invalidatePid(session, pid.pid);
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
                } finally {
                    if (!fresh) invalidatePid(session, pid.pid);
                }
                ElmText.sleepQuiet(25);
            }
            if (driveCyclePid && cycle % DRIVE_CYCLE_EVERY == 0 && monitoring.get() && !userDisconnect
                    && !exclusiveRequest.get() && session == sessionGeneration.get()) {
                Elm327Client current = client;
                if (current != null && current.isConnected()) pollDriveCycle(session, current);
            }
            updatePassiveDiagnostics();
            ElmText.sleepQuiet(70);
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
                if (session != sessionGeneration.get() || userDisconnect) return;
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

    /** Diagnoseabläufe; greifen über diesen Host auf die Sitzung zu. */
    private final DiagnosticJobs jobs = new DiagnosticJobs(new DiagnosticJobs.Host() {
        @Override public void log(String line) { append(line); }
        @Override public Elm327Client.CommandResult diag(Elm327Client c, String cmd, int timeoutMs) throws IOException {
            return diagCommand(c, cmd, timeoutMs);
        }
        @Override public void drop(Elm327Client c) { closeClientIfCurrent(c); }
        @Override public String protocolNumber() { return protocolNumber; }
        @Override public String protocol() { return protocol; }
        @Override public Boolean protocolIsCan() { return protocolIsCan; }
        @Override public void countTimeouts(long n) { timeouts.addAndGet(n); }
        @Override public void countIoError() { ioErrors.incrementAndGet(); }
        @Override public void status(String text) {
            int generation = activeDiagnosisGeneration;
            if (generation >= 0) showStatusForSession(generation, text);
        }
        @Override public boolean starterCancelled() { return starterCancel; }
        @Override public double recentAmbient() { return ObdSession.this.recentAmbient(); }
        @Override public boolean livePid(int pid) { return activePids.isEmpty() || containsPid(pid); }
        @Override public long now() { return SystemClock.elapsedRealtime(); }
    });

    private void reconnectAfterFailure(int session, String host, int port, ElmLink.Opener transport) {
        if (userDisconnect || !monitoring.get() || session != sessionGeneration.get()) return;
        showStatus("Verbindung unterbrochen · Reconnect …");
        try {
            ElmText.sleepQuiet(2000);
            if (session != sessionGeneration.get()) return;
            connectWithFallback(host, port, session, transport);
            for (String init : LinkPolicy.reconnectInit(protocolNumber)) {
                command(session, init, 1500);
            }
            pollTimeouts.reset();
            firstRequestAfterInit = true;
            latestValues.clear();
            latestSequences.clear();
            latestAt.clear();
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
        runExclusiveDiagnosis(Op.DTC, "OBD-II Fehlercodes", jobs::readDtcReport);
    }

    public void runMisfireAnalysis() {
        runExclusiveDiagnosis(Op.MISFIRE, "Aussetzer-Analyse", jobs::readMisfires);
    }

    public void runMonitorTests() {
        runExclusiveDiagnosis(Op.MONITOR_TESTS, "Monitortests (Mode 06)", jobs::readMonitorTests);
    }

    public void runInspectionCheck() {
        runExclusiveDiagnosis(Op.INSPECTION, "HU/AU-Vorab-Check", jobs::readInspection);
    }

    /** Fahrzeugprofil: Unterstützungs-Bitmaps 01/06/09 je Steuergerät (nur Lesen). */
    public void runVehicleProfile() {
        runExclusiveDiagnosis(Op.PROFILE, "Fahrzeugprofil", jobs::readVehicleProfile);
    }

    /** ATF-Temperatur der 7G-Tronic auf Knopfdruck (ADR-001). */
    public void runAtfTemperature() {
        runExclusiveDiagnosis(Op.ATF, "ATF-Temperatur (7G-Tronic)", jobs::readAtfTemperature);
    }

    /** Außentemperatur aus dem Live-Polling nur, wenn sie frisch ist. */
    static final long STARTER_AMBIENT_MAX_AGE_MS = 120_000;
    private volatile boolean starterCancel;
    private volatile boolean starterRunning;

    /**
     * Starter- und Batterietest (nur ATRV, liest die Adapterspannung, nichts am Fahrzeug): Ruhespannung,
     * Einbruch beim Anlassen, Ladespannung. Läuft er schon, bricht ein zweiter Aufruf ihn ab.
     */
    public void toggleStarterTest() {
        if (starterRunning) {
            starterCancel = true;
            showStatus("Starttest wird abgebrochen …");
            return;
        }
        starterCancel = false;
        // Schon ab der Annahme als laufend gelten: ein zweiter Druck, während der Auftrag noch wartet (laufende
        // Abfrage, synchronized), bricht ab statt „läuft bereits“ zu melden.
        starterRunning = true;
        if (!runExclusiveDiagnosis(Op.STARTER, "Starttest", this::readStarterTest)) starterRunning = false;
    }

    public boolean isStarterTestRunning() {
        return starterRunning;
    }

    private double recentAmbient() {
        Double v = latestValues.get(0x46);
        Long at = latestAt.get(0x46);
        if (v == null || at == null || SystemClock.elapsedRealtime() - at > STARTER_AMBIENT_MAX_AGE_MS) return Double.NaN;
        return v;
    }

    private String readStarterTest(Elm327Client c) throws IOException {
        starterRunning = true;
        try {
            return jobs.readStarterTest(c);
        } finally {
            starterRunning = false;
            starterCancel = false;
        }
    }

    /** Bus-Botschaften am OBD-Port passiv mitlesen (ADR-002): sendet nichts an das Fahrzeug. */
    public void runCanMonitor() {
        runExclusiveDiagnosis(Op.CAN_MONITOR, "Bus mitlesen", jobs::readCanMonitor);
    }

    /** ADR-004: Testfreigabe, ausgeliefert aus; die Apps setzen sie aus ihren Einstellungen. */
    private volatile boolean adr004Enabled;

    public void setAdr004Enabled(boolean enabled) {
        adr004Enabled = enabled;
    }

    public boolean isAdr004Enabled() {
        return adr004Enabled;
    }

    /** Kennung von Motor und Getriebe und Getriebe-Fehlerspeicher (ADR-004, nur lesend, nur mit Testfreigabe). */
    public void runKwpIdentDtc() {
        if (!adr004Enabled) {
            toast("Herstellerabfragen (ADR-004) sind in den Einstellungen ausgeschaltet");
            return;
        }
        runExclusiveDiagnosis(Op.KWP, "Kennung + Getriebe-Fehler (ADR-004)", jobs::readKwpIdentDtc);
    }

    private interface Diagnosis {
        String run(Elm327Client c) throws IOException;
    }

    /**
     * Führt eine Read-Only-Diagnose mit exklusivem Adapterzugriff aus (Live-Polling pausiert)
     * und zeigt das Ergebnis als Dialog. Timeouts werden wie beim DTC-Scan behandelt.
     */
    /** @return false, wenn der Auftrag nicht angenommen wurde (keine Verbindung, anderer Vorgang läuft) */
    private boolean runExclusiveDiagnosis(Op op, String title, Diagnosis job) {
        Elm327Client c = client;
        if (c == null || !c.isConnected()) {
            toast("Keine ELM327-Verbindung");
            return false;
        }
        if (fuelTrimTest.isRunning()) {
            toast("Fuel-Trim-Test läuft – bitte zuerst beenden");
            return false;
        }
        if (!exclusiveRequest.compareAndSet(false, true)) {
            toast("Diagnosevorgang läuft bereits");
            return false;
        }
        final int acceptedSession = sessionGeneration.get();
        activeDiagnosisGeneration = acceptedSession;
        setBusyForSession(acceptedSession, op, true);
        showStatusForSession(acceptedSession, title + " läuft …");

        io.execute(() -> {
            String report;
            try {
                synchronized (c) {
                    enterDiagnosis(c);
                    try {
                        report = job.run(c);
                    } finally {
                        // also after a parser error; an I/O error here drops the connection like any other
                        if (c.isConnected()) leaveDiagnosis(c);
                    }
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
                activeDiagnosisGeneration = -1;
                exclusiveRequest.set(false);
                // Scheitert schon enterDiagnosis, läuft readStarterTest nie – der Zustand darf nicht hängen bleiben.
                if (op == Op.STARTER) {
                    starterRunning = false;
                    starterCancel = false;
                }
            }
            // Trennen oder neuer Verbindungsaufbau entwertet den gesamten Auftrag.
            // Auch bereits gepostete UI-Callbacks prüfen dieselbe Sitzungskennung erneut.
            if (!diagnosisIsCurrent(acceptedSession)) return;
            setBusyForSession(acceptedSession, op, false);
            showStatusForSession(acceptedSession, statusAfterDiagnosis(title + " beendet · Reconnect läuft …"));
            updateStats();
            showResultForSession(acceptedSession, op, title, report);
        });
        return true;
    }

    /** Diagnose-Parser erwarten Antworten ohne Header; das Live-Polling auf 11-bit-CAN läuft mit ATH1. */
    private void enterDiagnosis(Elm327Client c) throws IOException {
        if (!LinkPolicy.DIAG_HEADERS.equals(LinkPolicy.liveHeaders(protocolNumber))) {
            c.sendCommand(LinkPolicy.DIAG_HEADERS, 1500);
        }
    }

    /**
     * Header-Zustand des Live-Pollings wiederherstellen. Schlägt das fehl, fliegt eine IOException und die
     * Verbindung wird verworfen; der Reconnect stellt den Zustand über {@link LinkPolicy#reconnectInit} her.
     */
    private void leaveDiagnosis(Elm327Client c) throws IOException {
        String live = LinkPolicy.liveHeaders(protocolNumber);
        if (!LinkPolicy.DIAG_HEADERS.equals(live)) c.sendCommand(live, 1500);
    }

    private String statusAfterDiagnosis(String reconnecting) {
        Elm327Client current = client;
        return current != null && current.isConnected()
                ? "Verbunden · " + elmId + " · " + protocol
                : reconnecting;
    }

    private Elm327Client.CommandResult diagCommand(Elm327Client c, String cmd, int timeoutMs) throws IOException {
        Elm327Client.CommandResult r = c.sendCommand(cmd, timeoutMs);
        append(cmd + " → " + ElmText.clean(r.raw));
        ElmText.sleepQuiet(60);
        return r;
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
        latestAt.clear();
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
        if (!CommandSafety.isAllowedInTerminal(cmd)) {
            String reason = CommandSafety.terminalBlockedReason(cmd);
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
        latestAt.clear();
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

    /** Ziel für Status und Benachrichtigung: „192.168.0.10:35000“ oder „Bluetooth OBDII“. */
    private static String target(String host, int port, ElmLink.Opener transport) {
        return transport != null ? transport.describe() : host + ":" + port;
    }

    private long connectWithFallback(String host, int port, int session, ElmLink.Opener bt) throws IOException {
        if (bt != null) {
            // ADR-005: kein WLAN-Routing; RFCOMM-Aufbau dauert bei Nachbauten bis zu einigen Sekunden.
            if (session != sessionGeneration.get()) throw new IOException("Veraltete Diagnose-Session");
            Elm327Client c = new Elm327Client(bt);
            try {
                long ms = c.connect(8000);
                installClientForSession(c, session);
                return ms;
            } catch (IOException e) {
                c.close();
                throw e;
            }
        }
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

    /** Sitzungswechsel dürfen weder alte Spinner erneut setzen noch neue Spinner entfernen. */
    private void setBusyForSession(int generation, Op op, boolean busy) {
        ui.post(() -> {
            if (!diagnosisIsCurrent(generation)) return;
            if (busy) state.busy.add(op);
            else state.busy.remove(op);
            if (listener != null) listener.onBusy(op, busy);
        });
    }

    /** Wird ausschließlich vom Main-Thread bei Trennen/Neuverbinden aufgerufen. */
    private void clearDiagnosisUiForSessionChange() {
        for (Op op : new ArrayList<>(state.busy)) {
            state.busy.remove(op);
            if (listener != null) listener.onBusy(op, false);
        }
        // Ein ohne Activity gespeichertes Ergebnis gehört zur alten Fahrzeugsitzung.
        pendingDialogs.removeIf(p -> p.op != null);
    }

    private boolean diagnosisIsCurrent(int generation) {
        return SessionEpoch.matches(generation, sessionGeneration.get(), active, userDisconnect);
    }

    private void showStatusForSession(int generation, String text) {
        ui.post(() -> {
            if (!diagnosisIsCurrent(generation)) return;
            state.status = text;
            if (listener != null) listener.onStatus(text);
            notifyObserver();
        });
    }

    private void setActive(boolean value) {
        active = value;
        ui.post(this::notifyObserver);
    }

    /** Dialoge, die ohne sichtbare UI eintreffen, werden beim nächsten Anbinden gezeigt. */
    private void showDialog(String title, String message) {
        deliver(null, title, message);
    }

    /**
     * Ergebnis einschließlich Prüfplaneintrag erst im UI-Thread übernehmen.
     * Der Guard läuft beim tatsächlichen Zustellen, nicht nur beim Beenden der I/O-Arbeit.
     */
    private void showResultForSession(int generation, Op op, String title, String message) {
        ui.post(() -> {
            if (!diagnosisIsCurrent(generation)) return;
            recordForPlan(op, message);
            if (listener != null) listener.onResult(op, title, message);
            else if (pendingDialogs.size() < MAX_PENDING_DIALOGS) {
                pendingDialogs.add(new Pending(op, title, message));
            }
        });
    }

    // ------------------------------------------------------------------ Prüfplan

    private final TestPlan testPlan = new TestPlan();
    private java.io.File testPlanFile;

    /** Prüfplan aus dem App-Speicher laden (einmal je App-Start); danach speichert jedes Ergebnis mit. */
    public void attachTestPlan(Context context) {
        java.io.File f = new java.io.File(context.getApplicationContext().getFilesDir(), "pruefplan.txt");
        synchronized (testPlan) {
            if (f.equals(testPlanFile)) return;
            testPlanFile = f;
            testPlan.clear();
            if (f.isFile()) {
                try {
                    String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    for (TestPlan.Record r : TestPlan.parse(text).records()) testPlan.record(r);
                } catch (IOException e) {
                    append("Prüfplan nicht lesbar: " + e.getMessage());
                }
            }
        }
    }

    private void recordForPlan(Op op, String message) {
        if (op == null) return;
        Double coolant = latestValues.get(0x05), rpm = latestValues.get(0x0C);
        synchronized (testPlan) {
            testPlan.record(new TestPlan.Record(op, System.currentTimeMillis(), coolant == null ? Double.NaN : coolant,
                    rpm == null ? Double.NaN : rpm, message));
            saveTestPlan();
        }
    }

    private void saveTestPlan() {
        if (testPlanFile == null) return;
        try {
            java.nio.file.Files.write(testPlanFile.toPath(), testPlan.serialize().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            append("Prüfplan nicht gespeichert: " + e.getMessage());
        }
    }

    public String testPlanChecklist() {
        synchronized (testPlan) {
            return testPlan.checklist();
        }
    }

    public int testPlanSize() {
        synchronized (testPlan) {
            return testPlan.records().size();
        }
    }

    public void clearTestPlan() {
        synchronized (testPlan) {
            testPlan.clear();
            saveTestPlan();
        }
    }

    /** Teilen-Dialog mit dem vollständigen Prüfplan-Bericht als Text. */
    public android.content.Intent testPlanShareIntent(String appVersion) {
        String text;
        synchronized (testPlan) {
            text = testPlan.report(java.time.ZoneId.systemDefault(), appVersion);
        }
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_SUBJECT, "Prüfplan C350 (OBD)")
                .putExtra(android.content.Intent.EXTRA_TEXT, text);
        return android.content.Intent.createChooser(send, "Prüfplan teilen");
    }

    private void deliver(Op op, String title, String message) {
        ui.post(() -> {
            if (listener != null) {
                if (op != null) listener.onResult(op, title, message);
                else listener.onDialog(title, message);
            } else if (pendingDialogs.size() < MAX_PENDING_DIALOGS) {
                pendingDialogs.add(new Pending(op, title, message));
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

}
