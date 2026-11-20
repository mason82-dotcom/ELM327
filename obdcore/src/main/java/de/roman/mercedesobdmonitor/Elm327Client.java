package de.roman.mercedesobdmonitor;

import android.net.Network;
import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public final class Elm327Client implements Closeable {
    public static final class CommandResult {
        public final String raw;
        public final long elapsedMs;

        CommandResult(String raw, long elapsedMs) {
            this.raw = raw;
            this.elapsedMs = elapsedMs;
        }
    }

    /** Monotone Uhr in ms; Tests ersetzen sie (SystemClock gibt es auf der JVM nicht). */
    static java.util.function.LongSupplier clock = SystemClock::elapsedRealtime;

    private static long now() {
        return clock.getAsLong();
    }

    private final ElmLink.Opener opener;
    private volatile ElmLink link;
    private BufferedInputStream in;
    private BufferedOutputStream out;
    // Geschrieben nur unter dem Lock (ein Schreiber), gelesen ohne: die Statistik darf ein laufendes
    // „Bus mitlesen“ (bis ~7 s unter dem Lock) nicht blockieren.
    private volatile long txBytes;
    private volatile long rxBytes;

    public Elm327Client(String host, int port, Network network) {
        this(ElmLink.Tcp.opener(host, port, network));
    }

    /** Beliebiger Transportweg (ADR-005: WLAN oder Bluetooth). */
    Elm327Client(ElmLink.Opener opener) {
        this.opener = opener;
    }

    /** Transportweg für Konsole und Status. */
    public String describe() {
        return opener.describe();
    }

    public long connect(int timeoutMs) throws IOException {
        header = CommandSafety.DEFAULT_HEADER; // a new ELM327 session starts with the default header
        close();
        long start = now();
        ElmLink l = opener.open(timeoutMs);
        link = l;
        in = new BufferedInputStream(l.input());
        out = new BufferedOutputStream(l.output());
        return now() - start;
    }

    /** Aktueller CAN-Header des Adapters (ADR-001); nach ATZ und jeder neuen Verbindung „7DF“. */
    private volatile String header = CommandSafety.DEFAULT_HEADER;

    public synchronized String header() {
        return header;
    }

    public synchronized CommandResult sendCommand(String command, int timeoutMs) throws IOException {
        ensureConnected();
        String cmd = CommandSafety.normalize(command);
        if (cmd.isEmpty()) throw new IllegalArgumentException("Leerer ELM327-Befehl");
        if (!CommandSafety.isAllowed(cmd, header)) {
            throw new IOException("Read-Only-Schutz: " + (CommandSafety.isAllowed(cmd)
                    ? "Befehl passt nicht zum aktuellen Header " + header + " (ADR-001/ADR-004)"
                    : CommandSafety.blockedReason(cmd)));
        }
        drainAvailable();
        // Until the adapter confirms a header command, its header is unknown: only ATSH7DF/ATZ may follow.
        if (CommandSafety.changesHeader(cmd)) header = CommandSafety.UNKNOWN_HEADER;
        byte[] data = (cmd + "\r").getBytes(StandardCharsets.US_ASCII);
        long start = now();
        out.write(data);
        out.flush();
        txBytes += data.length;
        link.setReadTimeout(timeoutMs);
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        try {
            while (true) {
                int b = in.read();
                if (b < 0) throw new IOException("ELM327 hat die Verbindung geschlossen");
                rxBytes++;
                if (b == '>') break;
                if (buf.size() < 64 * 1024) buf.write(b);
                else throw new IOException("ELM327-Antwort ist unerwartet groß");
            }
        } catch (SocketTimeoutException e) {
            String partial = new String(buf.toByteArray(), StandardCharsets.US_ASCII);
            throw new SocketTimeoutException("Timeout nach " + timeoutMs + " ms; Teilantwort: " + sanitize(partial));
        }
        long elapsed = now() - start;
        String raw = new String(buf.toByteArray(), StandardCharsets.US_ASCII).trim();
        // A '>' prompt only confirms the end of a reply, NOT that ATSH was accepted.
        // A rejected header command must never unlock an ECU-specific manufacturer request.
        if (CommandSafety.changesHeader(cmd)) {
            if (!headerAcknowledged(cmd, raw)) {
                // header was set to UNKNOWN_HEADER before transmission; keep it unknown.
                throw new IOException("ELM327 hat den Header-/Reset-Befehl nicht bestätigt: "
                        + cmd + " → " + sanitize(raw));
            }
            header = CommandSafety.headerAfter(cmd, header);
        }
        return new CommandResult(raw, elapsed);
    }

    /** Ergebnis von {@link #monitorPassive}: Rohtext, Dauer, wie der Lauf geendet hat. */
    public static final class MonitorResult {
        /** Nur vollständige Zeilen; eine abgeschnittene letzte Zeile ist verworfen. */
        public final String raw;
        public final long elapsedMs;
        /** true: '>' kam vor Ablauf (z. B. BUFFER FULL, „?“ bei Klonen ohne ATMA); false: per Stopp-Zeichen beendet. */
        public final boolean endedByAdapter;
        /** true: mehr Daten als {@code maxBytes} – der Rest wurde verworfen. */
        public final boolean truncated;
        /**
         * true: nach dem Stopp-Zeichen kam kein '>'. Die bis dahin gelesenen Daten gelten, der Adapter ist aber
         * nicht mehr synchron: der Aufrufer muss die Verbindung verwerfen.
         */
        public final boolean stopTimedOut;

        MonitorResult(String raw, long elapsedMs, boolean endedByAdapter, boolean truncated, boolean stopTimedOut) {
            this.raw = raw;
            this.elapsedMs = elapsedMs;
            this.endedByAdapter = endedByAdapter;
            this.truncated = truncated;
            this.stopTimedOut = stopTimedOut;
        }
    }

    /** Wartezeit auf den '>'-Prompt nach dem Stopp-Zeichen. */
    static final int MONITOR_STOP_TIMEOUT_MS = 2000;

    /**
     * ADR-002: Bus-Botschaften passiv mitlesen ({@code ATMA}), höchstens {@code durationMs} lang. ATMA sendet
     * nichts an das Fahrzeug, läuft aber ohne Prompt weiter; nach Ablauf stoppt ein einzelnes Leerzeichen den
     * Adapter (kein CR – das würde ATMA wiederholen; ein Leerzeichen ignoriert der ELM327 auch dann, wenn es
     * den Prompt knapp verpasst). Kommt danach kein '>', liefert das Ergebnis {@code stopTimedOut}: die Daten
     * bleiben erhalten, der Aufrufer verwirft die Verbindung, der Reconnect initialisiert den Adapter neu.
     */
    public synchronized MonitorResult monitorPassive(int durationMs, int maxBytes) throws IOException {
        ensureConnected();
        if (durationMs < 100 || durationMs > 30_000) throw new IllegalArgumentException("Mitlesedauer 0,1–30 s");
        drainAvailable();
        byte[] data = (CommandSafety.MONITOR_ALL + "\r").getBytes(StandardCharsets.US_ASCII);
        long start = now();
        out.write(data);
        out.flush();
        txBytes += data.length;
        MonitorBuffer buf = new MonitorBuffer(maxBytes);
        // Blockweise lesen: ein setSoTimeout je Block statt je Byte (voller Bus = Tausende Frames).
        if (buf.readUntilPrompt(start + durationMs)) {
            return new MonitorResult(buf.completeLines(), now() - start, true,
                    buf.truncated, false);
        }
        out.write(' ');
        out.flush();
        txBytes++;
        boolean prompt = buf.readUntilPrompt(now() + MONITOR_STOP_TIMEOUT_MS);
        return new MonitorResult(buf.completeLines(), now() - start, false,
                buf.truncated, !prompt);
    }

    /** Lesepuffer für {@link #monitorPassive}; nur unter dem Lock des Clients benutzt. */
    private final class MonitorBuffer {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
        private final byte[] chunk = new byte[2048];
        private final int maxBytes;
        boolean truncated;

        MonitorBuffer(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        /** true, sobald '>' kam; false bei Ablauf von {@code deadline}. */
        boolean readUntilPrompt(long deadline) throws IOException {
            while (true) {
                long left = deadline - now();
                if (left <= 0) return false;
                link.setReadTimeout((int) Math.max(1, left));
                int n;
                try {
                    n = in.read(chunk, 0, chunk.length);
                } catch (SocketTimeoutException e) {
                    return false;
                }
                if (n < 0) throw new IOException("ELM327 hat die Verbindung geschlossen");
                rxBytes += n;
                for (int i = 0; i < n; i++) {
                    if (chunk[i] == '>') {
                        // Rest nach dem Prompt gehört zu keiner Antwort mehr; nächster Befehl leert ohnehin.
                        return true;
                    }
                    if (buf.size() < maxBytes) buf.write(chunk[i]);
                    else truncated = true;
                }
            }
        }

        String completeLines() {
            return CanMonitor.completeLines(new String(buf.toByteArray(), StandardCharsets.US_ASCII));
        }
    }

    /**
     * Nach einem Timeout auf den verspäteten '>'-Prompt warten und alles bis dahin
     * verwerfen. Sendet selbst nichts (ein CR würde beim ELM327 den letzten Befehl
     * wiederholen). true = Adapter ist wieder synchron und bereit.
     */
    public synchronized boolean resync(int timeoutMs) {
        if (!isConnected() || in == null) return false;
        long deadline = now() + timeoutMs;
        try {
            while (true) {
                long left = deadline - now();
                if (left <= 0) return false;
                link.setReadTimeout((int) Math.max(1, left));
                int b = in.read();
                if (b < 0) return false;
                rxBytes++;
                if (b == '>') {
                    drainAvailable();
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
    }

    private void drainAvailable() throws IOException {
        if (in == null) return;
        int guard = 0;
        while (in.available() > 0 && guard < 8192) {
            int b = in.read();
            if (b < 0) break;
            rxBytes++;
            guard++;
        }
    }

    private void ensureConnected() throws IOException {
        ElmLink l = link;
        if (l == null || !l.isOpen()) {
            throw new IOException("Verbindung zum ELM327 ist nicht aktiv");
        }
    }

    public boolean isConnected() {
        ElmLink l = link;
        return l != null && l.isOpen();
    }

    public long getTxBytes() { return txBytes; }
    public long getRxBytes() { return rxBytes; }

    /**
     * Bewusst ohne Lock: ein im I/O-Thread blockierendes read() hält den Monitor;
     * das Schließen des Sockets bricht es sofort mit einer IOException ab, statt
     * den aufrufenden (ggf. Main-)Thread bis zum Timeout warten zu lassen.
     * in/out bleiben gesetzt – die Streams eines geschlossenen Sockets werfen IOException.
     */
    @Override
    public void close() {
        ElmLink l = link;
        if (l != null) l.close();
        opener.cancel();   // ein noch laufender Aufbau (Bluetooth) endet sofort
    }

    /**
     * ATSH muss als eigene Antwortzeile OK melden. ATZ liefert stattdessen üblicherweise
     * einen Identifikationstext; ein leeres oder offensichtlich abgewiesenes Reset ist kein Beleg.
     * Der Echo-Text (ATE1) darf nicht allein als Bestätigung gelten.
     */
    static boolean headerAcknowledged(String cmd, String raw) {
        if (raw == null || raw.isBlank()) return false;
        String[] lines = raw.toUpperCase(java.util.Locale.US).split("[\\r\\n]+");
        boolean ok = false;
        for (String line : lines) {
            String reply = line.trim();
            if ("?".equals(reply) || "ERROR".equals(reply) || reply.startsWith("UNABLE TO CONNECT")) {
                return false;
            }
            if ("OK".equals(reply)) ok = true;
        }
        if ("ATZ".equals(cmd)) return ok || !raw.trim().equalsIgnoreCase(cmd);
        return ok;
    }

    private static String sanitize(String s) {
        return s.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
