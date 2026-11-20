package de.roman.mercedesobdmonitor;

import android.net.Network;
import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
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

    private final String host;
    private final int port;
    private final Network network;
    private volatile Socket socket;
    private BufferedInputStream in;
    private BufferedOutputStream out;
    private long txBytes;
    private long rxBytes;

    public Elm327Client(String host, int port, Network network) {
        this.host = host;
        this.port = port;
        this.network = network;
    }

    public long connect(int timeoutMs) throws IOException {
        header = CommandSafety.DEFAULT_HEADER; // a new ELM327 session starts with the default header
        close();
        long start = SystemClock.elapsedRealtime();
        socket = network != null ? network.getSocketFactory().createSocket() : new Socket();
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        in = new BufferedInputStream(socket.getInputStream());
        out = new BufferedOutputStream(socket.getOutputStream());
        return SystemClock.elapsedRealtime() - start;
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
                    ? "Befehl passt nicht zum aktuellen Header " + header + " (ADR-001)"
                    : CommandSafety.blockedReason(cmd)));
        }
        drainAvailable();
        // Until the adapter confirms a header command, its header is unknown: only ATSH7DF/ATZ may follow.
        if (CommandSafety.changesHeader(cmd)) header = CommandSafety.UNKNOWN_HEADER;
        byte[] data = (cmd + "\r").getBytes(StandardCharsets.US_ASCII);
        long start = SystemClock.elapsedRealtime();
        out.write(data);
        out.flush();
        txBytes += data.length;
        socket.setSoTimeout(timeoutMs);
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        try {
            while (true) {
                int b = in.read();
                if (b < 0) throw new IOException("ELM327 hat die TCP-Verbindung geschlossen");
                rxBytes++;
                if (b == '>') break;
                if (buf.size() < 64 * 1024) buf.write(b);
                else throw new IOException("ELM327-Antwort ist unerwartet groß");
            }
        } catch (SocketTimeoutException e) {
            String partial = new String(buf.toByteArray(), StandardCharsets.US_ASCII);
            throw new SocketTimeoutException("Timeout nach " + timeoutMs + " ms; Teilantwort: " + sanitize(partial));
        }
        long elapsed = SystemClock.elapsedRealtime() - start;
        String raw = new String(buf.toByteArray(), StandardCharsets.US_ASCII).trim();
        // Header state follows only confirmed commands (see UNKNOWN_HEADER above).
        if (CommandSafety.changesHeader(cmd)) header = CommandSafety.headerAfter(cmd, header);
        return new CommandResult(raw, elapsed);
    }

    /** Ergebnis von {@link #monitorPassive}: Rohtext, Dauer, ob der Adapter selbst beendet hat. */
    public static final class MonitorResult {
        public final String raw;
        public final long elapsedMs;
        /** true: '>' kam vor Ablauf (z. B. BUFFER FULL, „?“ bei Klonen ohne ATMA); false: per Stopp-Zeichen beendet. */
        public final boolean endedByAdapter;
        /** true: mehr Daten als {@code maxBytes} – der Rest wurde verworfen. */
        public final boolean truncated;

        MonitorResult(String raw, long elapsedMs, boolean endedByAdapter, boolean truncated) {
            this.raw = raw;
            this.elapsedMs = elapsedMs;
            this.endedByAdapter = endedByAdapter;
            this.truncated = truncated;
        }
    }

    /** Wartezeit auf den '>'-Prompt nach dem Stopp-Zeichen. */
    static final int MONITOR_STOP_TIMEOUT_MS = 2000;

    /**
     * ADR-002: Bus-Botschaften passiv mitlesen ({@code ATMA}), höchstens {@code durationMs} lang. ATMA sendet
     * nichts an das Fahrzeug, läuft aber ohne Prompt weiter; nach Ablauf stoppt ein einzelnes Leerzeichen den
     * Adapter (kein CR – das würde ATMA wiederholen; ein Leerzeichen ignoriert der ELM327 auch dann, wenn es
     * den Prompt knapp verpasst). Kommt danach kein '>', fliegt eine {@link SocketTimeoutException}: der
     * Aufrufer verwirft die Verbindung, der Reconnect initialisiert den Adapter neu.
     */
    public synchronized MonitorResult monitorPassive(int durationMs, int maxBytes) throws IOException {
        ensureConnected();
        if (durationMs < 100 || durationMs > 30_000) throw new IllegalArgumentException("Mitlesedauer 0,1–30 s");
        drainAvailable();
        byte[] data = (CommandSafety.MONITOR_ALL + "\r").getBytes(StandardCharsets.US_ASCII);
        long start = SystemClock.elapsedRealtime();
        out.write(data);
        out.flush();
        txBytes += data.length;
        ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
        boolean truncated = false;
        long deadline = start + durationMs;
        try {
            while (true) {
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) break;
                socket.setSoTimeout((int) Math.max(1, left));
                int b;
                try {
                    b = in.read();
                } catch (SocketTimeoutException e) {
                    break; // Zeit abgelaufen, ohne dass der Adapter selbst geendet hat
                }
                if (b < 0) throw new IOException("ELM327 hat die TCP-Verbindung geschlossen");
                rxBytes++;
                if (b == '>') {
                    return new MonitorResult(text(buf), SystemClock.elapsedRealtime() - start, true, truncated);
                }
                if (buf.size() < maxBytes) buf.write(b);
                else truncated = true;
            }
            out.write(' ');
            out.flush();
            txBytes++;
            long stopDeadline = SystemClock.elapsedRealtime() + MONITOR_STOP_TIMEOUT_MS;
            while (true) {
                long left = stopDeadline - SystemClock.elapsedRealtime();
                if (left <= 0) throw new SocketTimeoutException("kein Prompt nach dem Stopp-Zeichen");
                socket.setSoTimeout((int) Math.max(1, left));
                int b = in.read();
                if (b < 0) throw new IOException("ELM327 hat die TCP-Verbindung geschlossen");
                rxBytes++;
                if (b == '>') break;
                if (buf.size() < maxBytes) buf.write(b);
                else truncated = true;
            }
        } catch (SocketTimeoutException e) {
            throw new SocketTimeoutException("Mitlesen nicht sauber beendet: " + e.getMessage());
        }
        return new MonitorResult(text(buf), SystemClock.elapsedRealtime() - start, false, truncated);
    }

    private static String text(ByteArrayOutputStream buf) {
        return new String(buf.toByteArray(), StandardCharsets.US_ASCII).trim();
    }

    /**
     * Nach einem Timeout auf den verspäteten '>'-Prompt warten und alles bis dahin
     * verwerfen. Sendet selbst nichts (ein CR würde beim ELM327 den letzten Befehl
     * wiederholen). true = Adapter ist wieder synchron und bereit.
     */
    public synchronized boolean resync(int timeoutMs) {
        if (!isConnected() || in == null) return false;
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        try {
            while (true) {
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) return false;
                socket.setSoTimeout((int) Math.max(1, left));
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
        if (socket == null || !socket.isConnected() || socket.isClosed()) {
            throw new IOException("TCP-Verbindung ist nicht aktiv");
        }
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public synchronized long getTxBytes() { return txBytes; }
    public synchronized long getRxBytes() { return rxBytes; }

    /**
     * Bewusst ohne Lock: ein im I/O-Thread blockierendes read() hält den Monitor;
     * das Schließen des Sockets bricht es sofort mit einer IOException ab, statt
     * den aufrufenden (ggf. Main-)Thread bis zum Timeout warten zu lassen.
     * in/out bleiben gesetzt – die Streams eines geschlossenen Sockets werfen IOException.
     */
    @Override
    public void close() {
        Socket s = socket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static String sanitize(String s) {
        return s.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
