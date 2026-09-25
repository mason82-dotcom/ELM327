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
    private Socket socket;
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

    public synchronized CommandResult sendCommand(String command, int timeoutMs) throws IOException {
        ensureConnected();
        String cmd = CommandSafety.normalize(command);
        if (cmd.isEmpty()) throw new IllegalArgumentException("Leerer ELM327-Befehl");
        if (!CommandSafety.isAllowed(cmd)) {
            throw new IOException("Read-Only-Schutz: " + CommandSafety.blockedReason(cmd));
        }
        drainAvailable();
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
        return new CommandResult(raw, elapsed);
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

    @Override
    public synchronized void close() {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        socket = null;
        in = null;
        out = null;
    }

    private static String sanitize(String s) {
        return s.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
