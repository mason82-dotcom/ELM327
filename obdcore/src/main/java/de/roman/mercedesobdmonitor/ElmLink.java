package de.roman.mercedesobdmonitor;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Transportweg zum ELM327 (ADR-005): WLAN/TCP oder Bluetooth (SPP). Der Client braucht nur zwei Ströme, einen
 * Lese-Timeout, der {@link java.net.SocketTimeoutException} wirft, und ein Schließen, das ein blockierendes Lesen
 * sofort abbricht.
 */
interface ElmLink extends Closeable {
    InputStream input() throws IOException;

    OutputStream output() throws IOException;

    /** Lese-Timeout für das nächste {@code read}; Ablauf wirft {@link java.net.SocketTimeoutException}. */
    void setReadTimeout(int ms) throws IOException;

    boolean isOpen();

    /** Kurzbeschreibung für Konsole und Status, z. B. „TCP 192.168.0.10:35000“. */
    String describe();

    @Override
    void close();

    /** Öffnet einen Transportweg (wird bei jedem Verbinden neu aufgerufen). */
    interface Opener {
        ElmLink open(int timeoutMs) throws IOException;

        String describe();

        /** Bricht einen gerade laufenden {@link #open} ab (Trennen während des Verbindens). */
        default void cancel() { }
    }

    /** WLAN/TCP – das bisherige Verhalten. */
    final class Tcp implements ElmLink {
        private final Socket socket;
        private final String text;

        private Tcp(Socket socket, String text) {
            this.socket = socket;
            this.text = text;
        }

        static Opener opener(String host, int port, android.net.Network network) {
            return new Opener() {
                @Override
                public ElmLink open(int timeoutMs) throws IOException {
                    Socket s = network != null ? network.getSocketFactory().createSocket() : new Socket();
                    try {
                        s.setTcpNoDelay(true);
                        s.setKeepAlive(true);
                        s.connect(new InetSocketAddress(host, port), timeoutMs);
                    } catch (IOException e) {
                        try { s.close(); } catch (IOException ignored) { }
                        throw e;
                    }
                    return new Tcp(s, describe());
                }

                @Override
                public String describe() {
                    return "TCP " + host + ":" + port;
                }
            };
        }

        @Override public InputStream input() throws IOException { return socket.getInputStream(); }
        @Override public OutputStream output() throws IOException { return socket.getOutputStream(); }
        @Override public void setReadTimeout(int ms) throws IOException { socket.setSoTimeout(ms); }
        @Override public boolean isOpen() { return socket.isConnected() && !socket.isClosed(); }
        @Override public String describe() { return text; }

        @Override
        public void close() {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
