package de.roman.testsupport;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Helpers for UI tests against the ELM327 simulator (tools/elm327-emulator), run by tools/android-emulator/uitest.sh.
 *
 * Instrumentation arguments: elmHost (default 192.168.178.50), elmPort (35000), deadPort (35009, nothing listens),
 * proxyPort (proxy.py hot,dtc), blocked (terminal commands to refuse); tests that need the last two are skipped
 * without them.
 */
public final class ElmTest {
    private static final String TAG = "ElmTest";

    private ElmTest() {
    }

    public static Bundle args() {
        return InstrumentationRegistry.getArguments();
    }

    public static String host() {
        return args().getString("elmHost", "192.168.178.50");
    }

    public static int port(String key, int fallback) {
        String v = args().getString(key);
        return v == null ? fallback : Integer.parseInt(v);
    }

    public static int simulatorPort() {
        return port("elmPort", 35000);
    }

    public static int deadPort() {
        return port("deadPort", 35009);
    }

    /** Port of proxy.py hot,dtc; skips the calling test when the runner started no proxy. */
    public static int proxyPort() {
        String v = args().getString("proxyPort");
        Assume.assumeTrue("proxyPort not given (proxy.py hot,dtc)", v != null);
        return Integer.parseInt(v);
    }

    /**
     * Commands the terminal must refuse, from uitest.sh (argument "blocked", ',' between commands, '_' for spaces –
     * adb passes neither spaces nor '|' unchanged to the device shell). One list in uitest.sh feeds the test and the proxy-log check.
     */
    public static String[] blockedCommands() {
        String v = args().getString("blocked");
        Assume.assumeTrue("blocked not given (uitest.sh)", v != null && !v.isEmpty());
        return v.replace('_', ' ').split(",");
    }

    /** Repeats a check (usually an Espresso assertion) until it passes; after the timeout its last failure is thrown. */
    public static void poll(Runnable check, long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (true) {
            try {
                check.run();
                return;
            } catch (AssertionError | RuntimeException e) {
                if (SystemClock.uptimeMillis() > end) throw e;
                SystemClock.sleep(250);
            }
        }
    }

    /**
     * The simulator serves one client at a time, like a Wi-Fi ELM327: after a test it must answer again before the
     * next test connects (otherwise that connection waits behind the old one and times out). Fails clearly if a
     * connection (app or proxy.py) was not released.
     */
    public static void awaitSimulatorFree() {
        long start = SystemClock.uptimeMillis();
        boolean free = answers(host(), simulatorPort(), 15_000);
        long ms = SystemClock.uptimeMillis() - start;
        Log.i(TAG, "simulator " + (free ? "free" : "still busy") + " after " + ms + " ms");
        if (!free) throw new AssertionError("ELM327-Simulator nach " + ms + " ms noch belegt – eine Verbindung "
                + "(App oder proxy.py) wurde nach dem Trennen nicht freigegeben");
    }

    /** ATZ until the prompt; plain socket from the test thread. Closing after the full answer avoids the echo crash. */
    public static boolean answers(String host, int port, long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 2_000);
                s.setSoTimeout(2_000);
                s.getOutputStream().write("ATZ\r".getBytes(StandardCharsets.US_ASCII));
                InputStream in = s.getInputStream();
                int c;
                while ((c = in.read()) >= 0) {
                    if (c == '>') return true;
                }
                Log.i(TAG, "simulator probe: closed before the prompt");
            } catch (IOException e) {
                Log.i(TAG, "simulator probe: " + e);
            }
            SystemClock.sleep(300);   // also after an early close: no tight reconnect loop
        }
        return false;
    }
}
