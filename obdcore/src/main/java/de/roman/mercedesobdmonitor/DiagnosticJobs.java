package de.roman.mercedesobdmonitor;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Die exklusiven Diagnoseabläufe (DTC, Mode 06, HU/AU, Fahrzeugprofil, ATF, Kennung/Getriebe-Fehler, Bus mitlesen,
 * Starttest) – aus {@link ObdSession} herausgelöst (Tech-Debt-Audit 20.11.2026, vorher ~1700 Zeilen in einer Klasse).
 * Jeder Ablauf bekommt den exklusiv gehaltenen Client; Sperre, Diagnose-Header und Fehlerbehandlung um den Ablauf
 * herum bleiben in {@code ObdSession#runExclusiveDiagnosis}. Alles, was ein Ablauf von der Sitzung braucht, kommt über
 * {@link Host} – damit sind die Abläufe ohne Android gegen einen ELM327-Nachbau testbar.
 */
final class DiagnosticJobs {
    /** Was die Abläufe von der Sitzung brauchen. */
    interface Host {
        /** Zeile ins Protokoll (Konsole). */
        void log(String line);
        /** Diagnose-Befehl senden und protokollieren. */
        Elm327Client.CommandResult diag(Elm327Client c, String cmd, int timeoutMs) throws IOException;
        /** Verbindung verwerfen (Reconnect übernimmt die Sitzung). */
        void drop(Elm327Client c);
        String protocolNumber();
        String protocol();
        Boolean protocolIsCan();
        void countTimeouts(long n);
        void countIoError();
        /** Statuszeile (Fortschritt). */
        void status(String text);
        boolean starterCancelled();
        /** Abbruch eines DTC-Leseauftrags, z. B. beim Schließen des blockierenden Adapter-Sockets. */
        boolean dtcCancelled();
        /** Außentemperatur (01 46), nur wenn frisch; sonst NaN. */
        double recentAmbient();
        /** Ob ein PID im Live-Polling aktiv ist (ohne erkannte PIDs: alle). */
        boolean livePid(int pid);
        /** Monotone Uhr in ms (Starttest). */
        long now();
    }

    private final Host host;

    DiagnosticJobs(Host host) {
        this.host = host;
    }

    /** Antwortzeit je ATRV-Probe im Starttest; ATRV misst im Adapter, ohne Fahrzeugbus. */
    static final int STARTER_ATRV_TIMEOUT_MS = 800;

    /** Mitlesedauer und Datenlimit für „Bus mitlesen“ (ADR-002). */
    static final int CAN_MONITOR_MS = 5000;
    static final int CAN_MONITOR_MAX_BYTES = 256 * 1024;

    /**
     * Mode 03/07/0A und M272-Hinweise. Läuft über {@link #runExclusiveDiagnosis} wie jede andere Diagnose (vorher eine
     * eigene Kopie davon: ein I/O-Fehler ließ die Verbindung im Diagnosezustand stehen – Tech-Debt-Audit 20.11.2026).
     */
    String readDtcReport(Elm327Client c) throws IOException {
        StringBuilder sb = new StringBuilder();
        List<String> allCodes = new ArrayList<>();
        sb.append("Generische OBD-II-Fehlercodes\n\n");
        sb.append(readDtcMode(c, "Gespeichert", "03", 0x43, allCodes));
        ElmText.sleepQuiet(180);
        sb.append("\n\n").append(readDtcMode(c, "Pending", "07", 0x47, allCodes));
        ElmText.sleepQuiet(180);
        sb.append("\n\n").append(readDtcMode(c, "Permanent", "0A", 0x4A, allCodes));
        ElmText.sleepQuiet(250);
        // Harmlose Read-only-Probe: scheitert sie, bleibt das Ergebnis, die Verbindung wird neu aufgebaut.
        if (!host.dtcCancelled() && c.isConnected()) {
            try {
                c.sendCommand("0100", 2200);
            } catch (IOException probeError) {
                host.log("DTC-Nachtest: Verbindung wird neu aufgebaut – " + probeError.getMessage());
                host.drop(c);
            }
        }
        return sb.append(m272Section(allCodes)).toString();
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
        if (host.dtcCancelled()) return label + ": abgebrochen";
        if (!c.isConnected()) {
            // Ein vorheriger Modus hat die Verbindung bereits verworfen (Timeout/I/O).
            return label + ": übersprungen · Verbindung wird neu aufgebaut";
        }
        try {
            Elm327Client.CommandResult r = c.sendCommand(cmd, 3500);
            String raw = ElmText.clean(r.raw);
            host.log("DTC " + label + " [" + cmd + "]: " + raw);
            List<String> codes = ObdParser.dtcs(r.raw, responseMode, host.protocolIsCan());
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
                if (ObdParser.isEmptyDtcResponse(r.raw, responseMode, host.protocolIsCan())) {
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
            if (host.dtcCancelled()) return label + ": abgebrochen";
            // Wie beim Live-Polling: auf den verspäteten Prompt warten; neu verbinden
            // nur, wenn der Adapter nicht wieder synchron wird.
            boolean synced = c.resync(1500);
            // STOP während des Resync: weder als Timeout melden noch die Verbindung ein zweites Mal verwerfen
            if (host.dtcCancelled()) return label + ": abgebrochen";
            host.countTimeouts(1);
            if (synced) {
                host.log("DTC " + label + ": Timeout · Prompt resynchronisiert, Verbindung bleibt");
                return label + ": Timeout – nicht gelesen";
            }
            host.log("DTC " + label + ": Timeout · kein Prompt, Verbindung wird neu aufgebaut");
            host.drop(c);
            return label + ": Timeout · Verbindung wird neu aufgebaut";
        } catch (IOException e) {
            if (host.dtcCancelled()) return label + ": abgebrochen";
            host.countIoError();
            host.drop(c);
            return label + ": Kommunikationsfehler – " + e.getMessage();
        }
    }

    String readCanMonitor(Elm327Client c) throws IOException {
        if (!LinkPolicy.isCan11Bit(host.protocolNumber())) {
            return "Mitlesen wertet nur CAN mit 11-bit-Kennungen (ISO 15765-4) aus. Erkanntes Protokoll: " + host.protocol();
        }
        boolean restored = false;
        Elm327Client.MonitorResult m;
        try {
            host.diag(c, "ATH1", 1500);
            host.diag(c, CommandSafety.RAW_FORMAT, 1500);
            m = c.monitorPassive(CAN_MONITOR_MS, CAN_MONITOR_MAX_BYTES);
            host.log(CommandSafety.MONITOR_ALL + " → " + m.raw.length() + " Zeichen in " + m.elapsedMs + " ms"
                    + (m.endedByAdapter ? " (vom Adapter beendet)" : "")
                    + (m.stopTimedOut ? " (kein Prompt nach dem Stopp – Verbindung wird neu aufgebaut)" : ""));
            if (!m.stopTimedOut) {
                host.diag(c, CommandSafety.AUTO_FORMAT, 1500);
                host.diag(c, LinkPolicy.DIAG_HEADERS, 1500);
                restored = true;
            }
        } finally {
            // ATCAF0 darf nicht stehen bleiben, sonst lesen die OBD-Parser rohe Frames: ohne bestätigte
            // Wiederherstellung Verbindung verwerfen, LinkPolicy.reconnectInit setzt ATCAF1.
            if (!restored) host.drop(c);
        }
        String report = CanMonitor.report(CanMonitor.parse(m.raw), m.elapsedMs, m.truncated);
        return m.stopTimedOut
                ? report + "\n\nHinweis: Der Adapter hat das Mitlesen nicht sauber beendet; die Verbindung wird neu aufgebaut."
                : report;
    }

    String readVehicleProfile(Elm327Client c) throws IOException {
        if (!LinkPolicy.isCan11Bit(host.protocolNumber())) {
            return "Das Fahrzeugprofil trennt die Steuergeräte über 11-bit-CAN-Kennungen (7E8/7E9); erkanntes Protokoll: "
                    + host.protocol() + ". Die unterstützten Werte zeigt die Werte-Seite.";
        }
        VehicleScan.Result r = new VehicleScan.Result();
        boolean done = false;
        // Headers on, so engine (7E8) and transmission (7E9) answers can be told apart. The profile parser reads
        // headers itself; afterwards the diagnosis state (ATH0) is restored, leaveDiagnosis() then the live state.
        host.diag(c, "ATH1", 1500);
        try {
            scanBitmaps(c, r, "01", 0x41, r.mode01, 0xA0);
            scanBitmaps(c, r, "06", 0x46, r.mode06, 0xE0);
            String raw = host.diag(c, "0900", 3000).raw;
            r.raw.put("0900", raw);
            r.merge(r.mode09, VehicleScan.supportedByEcu(raw, 0x49, 0x00));
            host.diag(c, "ATH0", 1500);
            done = true;
        } finally {
            // Without a confirmed ATH0 the adapter state is unknown: reconnect re-initialises it.
            if (!done) host.drop(c);
        }
        return VehicleScan.report(r);
    }

    String readKwpIdentDtc(Elm327Client c) throws IOException {
        if (!LinkPolicy.isCan11Bit(host.protocolNumber())) {
            return "Kennung und Getriebe-Fehler gehen nur über CAN mit 11-bit-Kennungen. Erkanntes Protokoll: " + host.protocol();
        }
        StringBuilder sb = new StringBuilder();
        boolean restored = false;
        try {
            host.diag(c, CommandSafety.ENGINE_HEADER, 1500);
            sb.append(KwpReadout.ident("Motor (7E0)", host.diag(c, CommandSafety.IDENT_REQUEST, 3000).raw));
            host.diag(c, CommandSafety.OBD_HEADER, 1500);
            ElmText.sleepQuiet(100);
            host.diag(c, CommandSafety.ATF_HEADER, 1500);
            sb.append("\n\n").append(KwpReadout.ident("Getriebe (7E1)", host.diag(c, CommandSafety.IDENT_REQUEST, 3000).raw));
            ElmText.sleepQuiet(100);
            sb.append("\n\n").append(KwpReadout.dtcs(host.diag(c, CommandSafety.TCU_DTC_REQUEST, 3000).raw));
            host.diag(c, CommandSafety.OBD_HEADER, 1500);
            restored = true;
        } finally {
            // wie ADR-001: der Header darf nie auf einem Steuergerät stehen bleiben
            if (!restored || !CommandSafety.DEFAULT_HEADER.equals(c.header())) host.drop(c);
        }
        sb.append("\n\nTestfreigabe ADR-004: Formate nach Referenz (ecu_diagnostics), am C350 ungeprüft. ")
                .append("Bitte Rohantworten in docs/adr-004-kwp-kennung-fehlerspeicher.md eintragen.");
        return sb.toString();
    }

    String readAtfTemperature(Elm327Client c) throws IOException {
        if (!LinkPolicy.isCan11Bit(host.protocolNumber())) {
            return "Die ATF-Temperatur wird nur über CAN mit 11-bit-Kennungen (ISO 15765-4) gelesen. Erkanntes Protokoll: "
                    + host.protocol();
        }
        boolean restored = false;
        try {
            host.diag(c, CommandSafety.ATF_HEADER, 1500);
            AtfTemperature.Reading reading = AtfTemperature.parse(host.diag(c, CommandSafety.ATF_REQUEST, 3000).raw);
            host.diag(c, CommandSafety.OBD_HEADER, 1500);
            restored = true;
            return reading.text();
        } finally {
            // ADR-001: the header must never stay on the transmission ECU. If restoring failed, drop the
            // connection: the reconnect init sends ATSH7DF on 11-bit CAN (LinkPolicy.reconnectInit).
            if (!restored || !CommandSafety.DEFAULT_HEADER.equals(c.header())) host.drop(c);
        }
    }

    /** Mode 06: OBDMID A2–A7 = Aussetzer Zylinder 1–6 (M272). */
    String readMisfires(Elm327Client c) throws IOException {
        if (Boolean.FALSE.equals(host.protocolIsCan())) {
            return "Mode 06 wird nur für CAN (ISO 15765-4) ausgewertet. Erkanntes Protokoll: " + host.protocol();
        }
        Elm327Client.CommandResult sup = host.diag(c, Mode06.command(Mode06.MID_SUPPORT_A0), 3000);
        Set<Integer> mids = Mode06.supportedMids(sup.raw, Mode06.MID_SUPPORT_A0);
        boolean bitmapKnown = !mids.isEmpty();

        List<Mode06.Misfire> list = new ArrayList<>();
        for (int cyl = 1; cyl <= 6; cyl++) {
            int mid = Mode06.MID_MISFIRE_CYL1 + cyl - 1;
            if (bitmapKnown && !mids.contains(mid)) continue;
            Elm327Client.CommandResult r = host.diag(c, Mode06.command(mid), 3000);
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
    String readMonitorTests(Elm327Client c) throws IOException {
        if (Boolean.FALSE.equals(host.protocolIsCan())) {
            return "Mode 06 wird nur für CAN (ISO 15765-4) ausgewertet. Erkanntes Protokoll: " + host.protocol();
        }
        try {
            return Mode06Overview.report(Mode06Overview.read(new InspectionReader.Transport() {
                @Override
                public String send(String cmd, int timeoutMs) throws IOException {
                    return host.diag(c, cmd, timeoutMs).raw;
                }

                @Override
                public boolean resync() {
                    host.countTimeouts(1);
                    boolean ok = c.resync(1500);
                    host.log("Monitortests: Timeout · " + (ok ? "Prompt resynchronisiert, weiter" : "kein Prompt"));
                    return ok;
                }
            }));
        } catch (SocketTimeoutException e) {
            // Der abbrechende Timeout wurde schon in resync() gezählt; runExclusiveDiagnosis zählt ihn erneut.
            host.countTimeouts(-1);
            throw e;
        }
    }

    /**
     * HU/AU-Vorab-Check: 01 01, 03/07/0A, 01 21/30/31, Freeze Frame, 01 41, 09 02/04/06/08.
     * Einzelne Timeouts werden per Resync überbrückt (siehe InspectionReader).
     */
    String readInspection(Elm327Client c) throws IOException {
        InspectionReader reader = new InspectionReader(new InspectionReader.Transport() {
            @Override
            public String send(String cmd, int timeoutMs) throws IOException {
                return host.diag(c, cmd, timeoutMs).raw;
            }

            @Override
            public boolean resync() {
                boolean ok = c.resync(1500);
                host.log("HU/AU-Check: Timeout · " + (ok ? "Prompt resynchronisiert, weiter"
                        : "kein Prompt"));
                return ok;
            }
        }, host.protocolIsCan());
        try {
            String report = InspectionCheck.report(reader.read(
                    pid -> host.livePid(pid)));
            host.countTimeouts(reader.timeoutCount());
            return report;
        } catch (SocketTimeoutException e) {
            // Der abbrechende Timeout wird in runExclusiveDiagnosis gezählt.
            host.countTimeouts(Math.max(0, reader.timeoutCount() - 1));
            throw e;
        }
    }

    String readStarterTest(Elm327Client c) throws IOException {
        StarterTest t = new StarterTest(host.recentAmbient());
        boolean linkLost = false;
        long lastStatus = -1;
        int probes = 0;
        host.log("Starttest: ATRV-Abtastung läuft (Konsole schweigt bis zum Ende)");
        while (!t.finished()) {
            if (host.starterCancelled()) {
                t.abort("vom Benutzer abgebrochen.", false);
                break;
            }
            long now = host.now();
            try {
                Elm327Client.CommandResult r = c.sendCommand("ATRV", STARTER_ATRV_TIMEOUT_MS);
                probes++;
                t.offer(host.now(), StarterTest.parseVolts(r.raw));
            } catch (SocketTimeoutException e) {
                // Später eintreffende Antwort verwerfen; ohne Prompt ist der Adapter weg (Unterspannung?).
                if (!c.resync(1500)) {
                    t.abort("Adapter antwortet nicht mehr.", true);
                    linkLost = true;
                    break;
                }
                t.tick(host.now());
            } catch (IOException e) {
                t.abort("Verbindung zum Adapter verloren (" + e.getMessage() + ").", true);
                linkLost = true;
                break;
            }
            if (lastStatus < 0 || now - lastStatus >= 250) {
                host.status(t.status(now));
                lastStatus = now;
            }
        }
        host.log("Starttest: " + probes + " ATRV-Proben, alle ~" + t.sampleIntervalMs() + " ms");
        double rpm = Double.NaN;
        if (!linkLost && t.phase() == StarterTest.Phase.DONE) {
            try {
                byte[] d = ObdParser.mode01Data(host.diag(c, "010C", 2000).raw, 0x0C, 2);
                if (d != null) rpm = ((d[0] & 0xFF) * 256 + (d[1] & 0xFF)) / 4.0;
            } catch (SocketTimeoutException e) {
                if (!c.resync(1500)) linkLost = true;
            } catch (IOException e) {
                // Messung ist vollständig: Bericht behalten, nur die Verbindung neu aufbauen
                linkLost = true;
            }
        }
        // Nach einem Abriss wertet runExclusiveDiagnosis nichts mehr aus: Bericht liefern, Verbindung neu aufbauen.
        if (linkLost) host.drop(c);
        return t.report(rpm);
    }

    private void scanBitmaps(Elm327Client c, VehicleScan.Result r, String mode, int responseService,
                             java.util.Map<String, java.util.TreeSet<Integer>> target, int lastBase) throws IOException {
        for (int base = 0x00; base <= lastBase; base += 0x20) {
            String cmd = mode + String.format(java.util.Locale.US, "%02X", base);
            String raw = host.diag(c, cmd, 3000).raw;
            r.raw.put(cmd, raw);
            java.util.Map<String, java.util.TreeSet<Integer>> part = VehicleScan.supportedByEcu(raw, responseService, base);
            r.merge(target, part);
            if (!VehicleScan.anyHasNext(part, base)) break;
        }
    }
}
