package de.roman.mercedesobdmonitor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bluetooth-Classic-Transport (SPP/RFCOMM) für ELM327-Bluetooth-Adapter (ADR-005). Gekoppelt wird in den
 * Android-Einstellungen (PIN meist 1234 oder 0000); die App verbindet nur mit einem gekoppelten Gerät.
 * RFCOMM kennt weder Verbindungs- noch Lese-Timeout: ein Wächter bricht den Aufbau nach der Frist ab,
 * {@link TimedInputStream} liefert den Lese-Timeout.
 */
final class BluetoothLink implements ElmLink {
    /** Serial Port Profile. */
    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothSocket socket;
    private final TimedInputStream in;
    private final String text;
    private volatile boolean closed;

    private BluetoothLink(BluetoothSocket socket, String text) throws IOException {
        this.socket = socket;
        this.in = new TimedInputStream(socket.getInputStream(), 64 * 1024, "elm-bt-reader");
        this.text = text;
    }

    /** Ein gekoppeltes Gerät: Name und Adresse. */
    static final class Paired {
        final String name;
        final String address;

        Paired(String name, String address) {
            this.name = name;
            this.address = address;
        }
    }

    static boolean hasPermission(Context c) {
        return Build.VERSION.SDK_INT < 31
                || c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private static BluetoothAdapter adapter(Context c) throws IOException {
        BluetoothManager bm = c.getApplicationContext().getSystemService(BluetoothManager.class);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        if (a == null) throw new IOException("Dieses Gerät hat kein Bluetooth");
        return a;
    }

    /** Gekoppelte Geräte, Namen mit „OBD“/„ELM“/„V-LINK“ zuerst. */
    @SuppressLint("MissingPermission")   // geprüft über hasPermission
    static List<Paired> pairedDevices(Context c) throws IOException {
        if (!hasPermission(c)) throw new IOException("Bluetooth-Berechtigung fehlt");
        BluetoothAdapter a = adapter(c);
        if (!a.isEnabled()) throw new IOException("Bluetooth ist ausgeschaltet");
        List<Paired> obd = new ArrayList<>(), other = new ArrayList<>();
        for (BluetoothDevice d : a.getBondedDevices()) {
            String name = d.getName() == null ? d.getAddress() : d.getName();
            String u = name.toUpperCase(java.util.Locale.ROOT);
            (u.contains("OBD") || u.contains("ELM") || u.contains("V-LINK") || u.contains("VLINK") ? obd : other)
                    .add(new Paired(name, d.getAddress()));
        }
        obd.addAll(other);
        return obd;
    }

    /** Öffner für ein gekoppeltes Gerät; hält keinen Context. */
    static Opener opener(Context c, String address, String name) throws IOException {
        if (!hasPermission(c)) throw new IOException("Bluetooth-Berechtigung fehlt");
        BluetoothAdapter a = adapter(c);
        if (!BluetoothAdapter.checkBluetoothAddress(address)) throw new IOException("Ungültige Bluetooth-Adresse " + address);
        BluetoothDevice device = a.getRemoteDevice(address);
        String label = "Bluetooth " + (name == null || name.isEmpty() ? address : name);
        java.util.concurrent.atomic.AtomicReference<BluetoothSocket> pending = new java.util.concurrent.atomic.AtomicReference<>();
        return new Opener() {
            @Override
            public ElmLink open(int timeoutMs) throws IOException {
                if (!a.isEnabled()) throw new IOException("Bluetooth ist ausgeschaltet");
                // eine Frist für beide Versuche zusammen (Review: vorher bis zur doppelten Zeit)
                long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
                IOException first;
                try {
                    return connect(device.createRfcommSocketToServiceRecord(SPP), remaining(deadline), label, pending);
                } catch (IOException e) {
                    first = e;
                }
                int left = remaining(deadline);
                if (left < 500) throw new IOException(label + " nicht erreichbar: " + first.getMessage());
                // Manche ELM327-Nachbauten verlangen eine unverschlüsselte Verbindung.
                try {
                    return connect(device.createInsecureRfcommSocketToServiceRecord(SPP), left, label, pending);
                } catch (IOException e) {
                    throw new IOException(label + " nicht erreichbar: " + first.getMessage()
                            + " / ohne Verschlüsselung: " + e.getMessage());
                }
            }

            @Override
            public String describe() {
                return label;
            }

            @Override
            public void cancel() {
                BluetoothSocket s = pending.getAndSet(null);
                if (s != null) {
                    try { s.close(); } catch (IOException ignored) { }
                }
            }
        };
    }

    private static int remaining(long deadline) {
        return (int) Math.max(0, (deadline - System.nanoTime()) / 1_000_000L);
    }

    @SuppressLint("MissingPermission")   // Aufrufer hat die Berechtigung geprüft (opener)
    private static ElmLink connect(BluetoothSocket s, int timeoutMs, String label,
                                   java.util.concurrent.atomic.AtomicReference<BluetoothSocket> pending) throws IOException {
        // Genau einer gewinnt: Wächter (schließt) oder Verbindung (fertig) – kein Schließen eines frisch verbundenen
        // Sockets mehr (Review)
        AtomicBoolean settled = new AtomicBoolean();
        AtomicBoolean timedOut = new AtomicBoolean();
        pending.set(s);
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(Math.max(1, timeoutMs));
                if (settled.compareAndSet(false, true)) {
                    timedOut.set(true);
                    s.close();
                }
            } catch (InterruptedException ignored) {
                // verbunden oder abgebrochen
            } catch (IOException ignored) {
                // schon zu
            }
        }, "elm-bt-connect");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            s.connect();
        } catch (IOException e) {
            settled.set(true);
            watchdog.interrupt();
            pending.compareAndSet(s, null);
            try { s.close(); } catch (IOException ignored) { }
            throw timedOut.get() ? new IOException("keine Antwort nach " + timeoutMs + " ms", e) : e;
        }
        pending.compareAndSet(s, null);
        if (!settled.compareAndSet(false, true)) {
            // Der Wächter hat genau jetzt geschlossen
            throw new IOException("keine Antwort nach " + timeoutMs + " ms");
        }
        watchdog.interrupt();
        try {
            return new BluetoothLink(s, label);
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { }   // Kanal nicht belegt lassen (Review)
            throw e;
        }
    }

    @Override public InputStream input() { return in; }
    @Override public OutputStream output() throws IOException { return socket.getOutputStream(); }
    @Override public void setReadTimeout(int ms) { in.setTimeout(ms); }
    @Override public boolean isOpen() { return !closed && socket.isConnected(); }
    @Override public String describe() { return text; }

    @Override
    public void close() {
        closed = true;
        try { in.close(); } catch (IOException ignored) { }
        try { socket.close(); } catch (IOException ignored) { }
    }
}
