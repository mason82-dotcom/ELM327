package de.roman.mercedesobdmonitor;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        /** Gemischadaption: gespeicherter Verlauf ({@link MixtureAdaptation#serialize}) oder null. */
        default String previousMixtureAdaptation() { return null; }
        default void saveMixtureAdaptation(String text) { }
        /** Aktuelle Kühlmitteltemperatur aus dem Live-Polling, NaN wenn unbekannt. */
        default double liveCoolant() { return Double.NaN; }
        /** Wanduhr (ms seit 1970) für Zeitstempel. */
        default long wallClock() { return System.currentTimeMillis(); }
    }

    private final Host host;

    DiagnosticJobs(Host host) {
        this.host = host;
    }

    /** Antwortzeit je ATRV-Probe im Starttest; ATRV misst im Adapter, ohne Fahrzeugbus. */
    static final int STARTER_ATRV_TIMEOUT_MS = 800;

    /**
     * „Bus mitlesen“ (ADR-002): ungefilterter Überblick (lief am C350 nach 0,2 s über), dann je bekannter Kennung
     * gefiltert ({@link #CAN_FILTER_MS} je Kennung); Datenlimit je Lauf.
     */
    static final int CAN_MONITOR_MS = 1000;
    static final int CAN_FILTER_MS = 2000;
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
        if (!VehicleProfile.ACTIVE.m272Hints) return "";
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
            // vor dem Sammeln merken, welche Codes schon in einem früheren Modus standen (ME9.7-Zeile nur einmal)
            List<String> shownBefore = collected == null ? new ArrayList<>() : new ArrayList<>(collected);
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
                String me = VehicleProfile.ACTIVE.m272Hints && !shownBefore.contains(code) ? Me97Codes.describe(code) : null;
                shownBefore.add(code);
                // mehrere Mercedes-Teilfehler je P-Code („·“ oder „/“): gemeint ist genau einer davon
                if (me != null) sb.append(Me97Codes.hasAlternatives(me) ? "\n   ME9.7, einer von: " : "\n   ME9.7: ").append(me);
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
        // Vergleich vor dem Mitlesen: antwortet das Fahrzeug überhaupt (Zündung aus → die Prüfanfrage danach sagt
        // nichts über den Adapter)?
        boolean vehicleAnswers = obdAnswers(host.diag(c, "0100", 2000).raw);
        boolean restored = false;
        boolean stuck = false;
        String interrupted = null;
        String filterSkipped = null;
        String notRestored = null;
        boolean filterSet = false;   // ein ATCRA<id> ging hinaus – nur dann muss das Zurücksetzen bestätigt werden
        Elm327Client.MonitorResult overview;
        CanMonitor.Result overviewResult;
        Map<Integer, CanMonitor.FilteredRun> filtered = new LinkedHashMap<>();
        try {
            host.diag(c, "ATH1", 1500);
            host.diag(c, CommandSafety.RAW_FORMAT, 1500);
            overview = monitor(c, "ungefiltert", CAN_MONITOR_MS);
            overviewResult = CanMonitor.parse(overview.raw);
            stuck = overview.stopTimedOut;
            try {
                if (!stuck && overviewResult.unsupported) {
                    filterSkipped = "der Adapter kennt ATMA nicht";
                } else if (!stuck && !isOk(host.diag(c, CommandSafety.RECEIVE_FILTER_RESET, 1500).raw)) {
                    // Nie einen Filter setzen, den dieser Adapter nicht zurücksetzen kann – er bliebe über jeden
                    // Neuaufbau stehen und blendete alle OBD-Antworten aus.
                    filterSkipped = "der Adapter kennt das Zurücksetzen des Empfangsfilters (ATCRA) nicht";
                }
                for (int id : CanMonitor.W204_POWERTRAIN.keySet()) {
                    if (stuck || filterSkipped != null) break;
                    filterSet = true;
                    if (!isOk(host.diag(c, CommandSafety.receiveFilter(id), 1500).raw)) {
                        filterSkipped = String.format(Locale.GERMANY, "der Adapter lehnt den Empfangsfilter %03X ab", id);
                        break;
                    }
                    Elm327Client.MonitorResult m = monitor(c, String.format(Locale.GERMANY, "Filter %03X", id), CAN_FILTER_MS);
                    stuck = m.stopTimedOut;
                    filtered.put(id, new CanMonitor.FilteredRun(CanMonitor.parse(m.raw), m.elapsedMs, m.truncated));
                }
                if (!stuck) {
                    // Ohne gesetzten Filter kein Zurücksetzen verlangen: Adapter ohne „ATCRA“ ohne Adresse (am C350,
                    // 22.11.) wurden sonst nach jedem Mitlesen grundlos neu verbunden.
                    if (filterSet && !isOk(host.diag(c, CommandSafety.RECEIVE_FILTER_RESET, 1500).raw)) {
                        notRestored = "der Adapter hat das Zurücksetzen des Empfangsfilters nicht bestätigt";
                    }
                    if (!isOk(host.diag(c, CommandSafety.AUTO_FORMAT, 1500).raw) && notRestored == null) {
                        notRestored = "der Adapter hat ATCAF1 (normales Format) nicht bestätigt";
                    }
                    if (!isOk(host.diag(c, LinkPolicy.DIAG_HEADERS, 1500).raw) && notRestored == null) {
                        notRestored = "der Adapter hat ATH0 nicht bestätigt";
                    }
                    // Prüfanfrage: eine 41-00-Antwort im normalen Format (ohne PCI-Byte) zeigt, dass Filter und Format
                    // zurück sind. Antwortete das Fahrzeug schon vorher nicht, zählen nur die Bestätigungen.
                    if (notRestored == null && vehicleAnswers && !obdAnswers(host.diag(c, "0100", 2000).raw)) {
                        notRestored = "nach dem Mitlesen kam keine gültige Antwort auf 01 00";
                    }
                    restored = notRestored == null;
                }
            } catch (IOException e) {
                // gesammelte Daten behalten; ohne bestätigte Wiederherstellung wird unten die Verbindung verworfen
                interrupted = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
        } finally {
            // ATCAF0 oder ein Filter dürfen nicht stehen bleiben, sonst lesen die OBD-Parser rohe oder gar keine
            // Antworten: ohne bestätigte Wiederherstellung Verbindung verwerfen (reconnectInit setzt beides zurück).
            if (!restored) host.drop(c);
        }
        StringBuilder report = new StringBuilder(CanMonitor.report(overviewResult, overview.elapsedMs, overview.truncated))
                .append(CanMonitor.filteredReport(filtered));
        if (filterSkipped != null) report.append("\n\nGefiltertes Mitlesen übersprungen: ").append(filterSkipped).append('.');
        if (stuck) {
            report.append("\n\nHinweis: Der Adapter hat das Mitlesen nicht sauber beendet; die Verbindung wird neu aufgebaut.");
        } else if (interrupted != null) {
            report.append("\n\nHinweis: Mitlesen unterbrochen (").append(interrupted)
                    .append("); die bisherigen Ergebnisse stehen oben, die Verbindung wird neu aufgebaut.");
        } else if (!restored) {
            report.append("\n\nHinweis: ").append(notRestored)
                    .append(" – Filter oder Format sind nicht sicher zurückgesetzt; die Verbindung wird neu aufgebaut.");
        } else if (!vehicleAnswers) {
            report.append("\n\nHinweis: Das Fahrzeug beantwortete 01 00 schon vor dem Mitlesen nicht (Zündung aus?); "
                    + "der Adapter hat das Zurücksetzen bestätigt.");
        }
        return report.toString();
    }

    private static boolean isOk(String raw) {
        return raw != null && raw.toUpperCase(Locale.US).contains("OK");
    }

    /** Gültige 01-00-Antwort im normalen Format: eine Nachricht beginnt mit 41 00 (kein PCI-Byte davor). */
    private static boolean obdAnswers(String raw) {
        for (String m : ObdParser.canMessages(raw)) if (m.startsWith("4100") && m.length() >= 12) return true;
        return false;
    }

    private Elm327Client.MonitorResult monitor(Elm327Client c, String what, int durationMs) throws IOException {
        Elm327Client.MonitorResult m = c.monitorPassive(durationMs, CAN_MONITOR_MAX_BYTES);
        host.log(CommandSafety.MONITOR_ALL + " (" + what + ") → " + m.raw.length() + " Zeichen in " + m.elapsedMs + " ms"
                + (m.endedByAdapter ? " (vom Adapter beendet)" : "")
                + (m.stopTimedOut ? " (kein Prompt nach dem Stopp – Verbindung wird neu aufgebaut)" : ""));
        return m;
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
            // Motor-Teil (7E0) verworfen: das ME9.7 antwortet dort nicht (Fahrzeugtest 21.11.2026)
            host.diag(c, CommandSafety.ATF_HEADER, 1500);
            sb.append(KwpReadout.ident("Getriebe (7E1)", host.diag(c, CommandSafety.IDENT_REQUEST, 3000).raw));
            ElmText.sleepQuiet(100);
            sb.append("\n\n").append(KwpReadout.dtcs(host.diag(c, CommandSafety.TCU_DTC_REQUEST, 3000).raw));
            host.diag(c, CommandSafety.OBD_HEADER, 1500);
            restored = true;
        } finally {
            // wie ADR-001: der Header darf nie auf einem Steuergerät stehen bleiben
            if (!restored || !CommandSafety.DEFAULT_HEADER.equals(c.header())) host.drop(c);
        }
        sb.append(sb.indexOf("Teilenummer:") >= 0
                ? "\n\nTestfreigabe ADR-004: Antwortformat am C350 bestätigt (21.11.2026)."
                : "\n\nTestfreigabe ADR-004: keine verwertbare Kennung in dieser Antwort.");
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
        // Vor dem Lesen: das Live-Polling pausiert während der Diagnose, danach ist der Wert oft schon verfallen.
        double coolant = host.liveCoolant();
        try {
            List<Mode06Overview.Test> tests = Mode06Overview.read(new InspectionReader.Transport() {
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
            });
            String report = Mode06Overview.report(tests);
            // Nur ME9.7 (Profil M272) und nur, wenn MID 81/82 überhaupt geliefert wurde
            if (!VehicleProfile.ACTIVE.m272Hints || !MixtureAdaptation.present(tests)) return report;
            // Gemischadaption direkt unter die Überschrift, vor die Einzeltests
            int nl = report.indexOf('\n');
            return report.substring(0, nl + 1) + "\n" + mixtureSection(c, tests, coolant) + "\n" + report.substring(nl + 1);
        } catch (SocketTimeoutException e) {
            // Der abbrechende Timeout wurde schon in resync() gezählt; runExclusiveDiagnosis zählt ihn erneut.
            host.countTimeouts(-1);
            throw e;
        }
    }

    /** Gemischadaption je Bank (MID 81/82) als eigener Abschnitt mit gespeichertem Verlauf. */
    private String mixtureSection(Elm327Client c, List<Mode06Overview.Test> tests, double coolant) throws IOException {
        if (Double.isNaN(coolant)) {
            Double v = ObdPid.of(0x05).parse(host.diag(c, "0105", 1500).raw);
            if (v != null) coolant = v;
        }
        // FIN, damit der Verlauf nur Lesungen desselben Fahrzeugs vergleicht
        String vin = VehicleInfo.vin(host.diag(c, "0902", 3000).raw, host.protocolIsCan());
        MixtureAdaptation history = MixtureAdaptation.parse(host.previousMixtureAdaptation());
        MixtureAdaptation.Reading now = MixtureAdaptation.from(tests, host.wallClock(), coolant, vin == null ? "" : vin);
        if (now.any()) {
            history.add(now);
            host.saveMixtureAdaptation(history.serialize());
        }
        return history.report(now, java.time.ZoneId.systemDefault());
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
        // Abgleich vor der Messung (Zündung an, Motor aus): Steuergerät-Spannung, damit die Startaufforderung nicht wartet
        t.setEcuRest(ecuVolts(c));
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
        // Abgleich nach der Ladungsmessung – eigener Schritt, damit ein Fehler bei 01 0C ihn nicht überspringt
        if (!linkLost && t.phase() == StarterTest.Phase.DONE) {
            try {
                t.setEcuCharge(ecuVolts(c));
            } catch (IOException e) {
                linkLost = true;
            }
        }
        // Nach einem Abriss wertet runExclusiveDiagnosis nichts mehr aus: Bericht liefern, Verbindung neu aufbauen.
        if (linkLost) host.drop(c);
        return t.report(rpm);
    }

    /**
     * Steuergerät-Spannung 01 42 in V (Formel aus {@link ObdPid}); NaN ohne Antwort. Direkt gesendet, damit die
     * Konsole während des Starttests still bleibt; eine Zeitüberschreitung zählt wie überall.
     */
    private double ecuVolts(Elm327Client c) throws IOException {
        try {
            Double v = ObdPid.of(0x42).parse(c.sendCommand("0142", 1000).raw);
            return v == null ? Double.NaN : v;
        } catch (SocketTimeoutException e) {
            host.countTimeouts(1);
            if (!c.resync(1500)) throw e;
            return Double.NaN;
        }
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
