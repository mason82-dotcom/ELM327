package de.roman.mercedesobdmonitor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;

/**
 * Lese-Timeout für Ströme ohne eigenen (Bluetooth-RFCOMM kennt kein {@code setSoTimeout}): ein Lesefaden puffert die
 * ankommenden Bytes, {@link #read} wartet höchstens {@link #setTimeout} ms und wirft dann
 * {@link SocketTimeoutException} – wie ein TCP-Socket. Das Schließen des darunterliegenden Stroms beendet den Faden.
 */
final class TimedInputStream extends InputStream {
    private final InputStream source;
    private final byte[] ring;
    private int head;   // nächstes zu lesendes Byte
    private int count;  // Bytes im Puffer
    private boolean eof;
    private IOException failure;
    private boolean closed;
    private volatile int timeoutMs;

    TimedInputStream(InputStream source, int capacity, String name) {
        this.source = source;
        this.ring = new byte[capacity];
        Thread pump = new Thread(this::pump, name);
        pump.setDaemon(true);
        pump.start();
    }

    /** 0 = unbegrenzt warten. */
    void setTimeout(int ms) {
        timeoutMs = ms;
    }

    private void pump() {
        byte[] chunk = new byte[512];
        try {
            while (true) {
                int n = source.read(chunk);
                synchronized (this) {
                    if (n < 0) {
                        eof = true;
                        notifyAll();
                        return;
                    }
                    for (int i = 0; i < n; i++) {
                        // Puffer voll: Leser wecken, dann auf Platz warten statt Daten zu verwerfen
                        while (count == ring.length && !closed) {
                            notifyAll();
                            wait();
                        }
                        if (closed) return;
                        ring[(head + count) % ring.length] = chunk[i];
                        count++;
                    }
                    notifyAll();
                }
            }
        } catch (IOException e) {
            synchronized (this) {
                failure = e;
                notifyAll();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized int read() throws IOException {
        if (!await()) return -1;
        int b = ring[head] & 0xFF;
        head = (head + 1) % ring.length;
        count--;
        notifyAll();
        return b;
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (!await()) return -1;
        int n = Math.min(len, count);
        for (int i = 0; i < n; i++) {
            b[off + i] = ring[head];
            head = (head + 1) % ring.length;
        }
        count -= n;
        notifyAll();
        return n;
    }

    /** true, sobald Daten da sind; false am Ende; wirft bei Timeout oder Lesefehler. */
    private boolean await() throws IOException {
        int timeout = timeoutMs;   // einmal lesen: ein paralleles setTimeout ändert dieses Warten nicht (Review)
        long deadline = timeout > 0 ? System.nanoTime() + timeout * 1_000_000L : Long.MAX_VALUE;
        while (count == 0) {
            // erst „geschlossen“: der Lesefehler des Fadens nach close() ist kein Fehler des Lesers (Review)
            if (closed || eof) return false;
            if (failure != null) throw failure;
            long left = (deadline - System.nanoTime()) / 1_000_000L;
            if (timeout > 0 && left <= 0) throw new SocketTimeoutException("Read timed out");
            try {
                wait(timeout > 0 ? Math.max(1, left) : 0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Lesen unterbrochen");
            }
        }
        return true;
    }

    /** false nach Ende, Lesefehler oder Schließen – die Verbindung darunter ist tot (Review: isOpen). */
    synchronized boolean alive() {
        return !eof && failure == null && !closed;
    }

    @Override
    public synchronized int available() {
        return count;
    }

    @Override
    public void close() throws IOException {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
        source.close();
    }
}
