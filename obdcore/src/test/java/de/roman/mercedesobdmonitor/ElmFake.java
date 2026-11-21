package de.roman.mercedesobdmonitor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * ELM327-Nachbau über einen lokalen Socket: nimmt Befehle bis CR, merkt sie in {@link #received} und antwortet mit
 * {@link #answers} (null = keine Antwort, Timeout beim Client). Eine Antwort mit Präfix {@code "@delay=<ms>;"}
 * kommt erst nach dieser Zeit – für verspätete Antworten (Resynchronisation).
 */
final class ElmFake implements AutoCloseable {
    volatile Function<String, String> answers = c -> "?";
    final List<String> received = Collections.synchronizedList(new ArrayList<>());
    private final ServerSocket server;
    private final Thread thread;

    ElmFake() throws IOException {
        server = new ServerSocket(0);
        thread = new Thread(this::serve);
        thread.start();
    }

    int port() {
        return server.getLocalPort();
    }

    /** Verbundener Client auf diesen Nachbau. */
    Elm327Client connect() throws IOException {
        Elm327Client client = new Elm327Client("127.0.0.1", port(), null);
        client.connect(2000);
        return client;
    }

    private void serve() {
        try (Socket s = server.accept()) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            StringBuilder cmd = new StringBuilder();
            int b;
            while ((b = in.read()) >= 0) {
                if (b != '\r') {
                    cmd.append((char) b);
                    continue;
                }
                String c = cmd.toString();
                cmd.setLength(0);
                received.add(c);
                String a = answers.apply(c);
                if (a == null) continue;
                if (a.startsWith("@delay=")) {
                    int end = a.indexOf(';');
                    sleep(Long.parseLong(a.substring(7, end)));
                    a = a.substring(end + 1);
                }
                out.write((a + "\r\r>").getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } catch (IOException ignored) {
            // Verbindung zu
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() throws Exception {
        server.close();
        thread.join(2000);
    }
}
