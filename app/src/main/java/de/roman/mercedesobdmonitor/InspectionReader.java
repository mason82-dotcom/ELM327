package de.roman.mercedesobdmonitor;

import java.io.IOException;
import java.util.Locale;
import java.util.function.IntPredicate;

/**
 * Liest die Daten des HU/AU-Vorab-Checks (Android-unabhängig, unit-testbar).
 *
 * Jede Abfrage ist einzeln fehlertolerant: Ein Timeout wird per Prompt-Resync
 * aufgefangen, die Abfrage als „nicht verfügbar“ markiert und der Check läuft
 * weiter. Nur wenn der Adapter nicht mehr synchron ist (Resync fehlgeschlagen
 * oder zu viele Timeouts in Folge) oder die Verbindung abbricht, wird der ganze
 * Check abgebrochen – dann ist ein Reconnect nötig.
 */
public final class InspectionReader {
    /** Zugriff auf den Adapter. Implementiert in ObdSession bzw. im Test. */
    public interface Transport {
        String send(String cmd, int timeoutMs) throws IOException;

        /** Wartet nach einem Timeout auf den verspäteten Prompt. true = synchron. */
        boolean resync();
    }

    private final TolerantQuery queries;
    private final Boolean isCan;

    public InspectionReader(Transport transport, Boolean isCan) {
        this.queries = new TolerantQuery(transport);
        this.isCan = isCan;
    }

    public int timeoutCount() {
        return queries.timeoutCount();
    }

    /**
     * @param freezeFramePid true, wenn der Freeze-Frame-Wert dieses PIDs gelesen werden soll
     */
    public InspectionCheck.Input read(IntPredicate freezeFramePid) throws IOException {
        InspectionCheck.Input in = new InspectionCheck.Input();

        String ready = query("0101", 3000);
        in.readiness = ready == null ? null : Readiness.parse(ready);
        if (in.readiness == null) in.unavailable.add("Readiness (01 01)");

        String stored = query("03", 3500);
        in.storedKnown = isValidDtcAnswer(stored, 0x43);
        if (in.storedKnown) in.stored = ObdParser.dtcs(stored, 0x43, isCan);
        else in.unavailable.add("gespeicherte DTCs (03)");

        String pending = query("07", 3500);
        in.pendingKnown = isValidDtcAnswer(pending, 0x47);
        if (in.pendingKnown) in.pending = ObdParser.dtcs(pending, 0x47, isCan);
        else in.unavailable.add("Pending-DTCs (07)");

        String perm = query("0A", 3500);
        in.permanentSupported = perm != null && ObdParser.hasDtcResponse(perm, 0x4A, isCan);
        if (in.permanentSupported) in.permanent = ObdParser.dtcs(perm, 0x4A, isCan);

        in.kmWithMil = optionalWord(0x21, "Strecke mit MIL (01 21)", in);
        in.warmupsSinceCleared = optionalByte(0x30, "Warmläufe seit Rücksetzen (01 30)", in);
        in.kmSinceCleared = optionalWord(0x31, "Strecke seit Rücksetzen (01 31)", in);

        String ffDtc = query("020200", 3000);
        in.freezeFrameDtc = ffDtc == null ? null : InspectionCheck.freezeFrameDtc(ffDtc);
        if (in.freezeFrameDtc != null) {
            for (ObdPid pid : ObdPid.defaultPids()) {
                if (!freezeFramePid.test(pid.pid)) continue;
                String raw = query(String.format(Locale.US, "02%02X00", pid.pid), 2500);
                if (raw == null || ObdParser.isNoData(raw)) continue;
                Double v = pid.parseFreezeFrame(raw);
                if (v != null) in.freezeFrame.add(pid.label + ": " + pid.format(v));
            }
        }

        // Zusatzinformationen: „NO DATA“ = nicht unterstützt (still), Timeout = nicht verfügbar.
        String cycle = query("0141", 2500);
        if (cycle == null) in.unavailable.add("Fahrzyklus-Monitore (01 41)");
        else in.driveCycle = Readiness.parseDriveCycle(cycle);

        String vin = query("0902", 3000);
        if (vin == null) in.unavailable.add("FIN (09 02)");
        else in.vin = VehicleInfo.vin(vin, isCan);

        String cal = query("0904", 3000);
        if (cal == null) in.unavailable.add("Kalibrierungs-IDs (09 04)");
        else in.calibrationIds = VehicleInfo.calibrationIds(cal, isCan);

        String cvn = query("0906", 3000);
        if (cvn == null) in.unavailable.add("CVN (09 06)");
        else in.cvns = VehicleInfo.cvns(cvn, isCan);

        String iupr = query("0908", 3000);
        if (iupr == null) in.unavailable.add("Monitor-Häufigkeit (09 08)");
        else in.iupr = VehicleInfo.iupr(iupr, isCan);
        return in;
    }

    /**
     * DTC-Liste nur als bekannt werten, wenn die Antwort gültig oder „NO DATA“ ist.
     * Bus-/Verbindungsfehler („UNABLE TO CONNECT“, „CAN ERROR“ …) bedeuten „nicht gelesen“,
     * nicht „keine Codes“.
     */
    private boolean isValidDtcAnswer(String raw, int mode) {
        if (raw == null || ObdParser.isLinkError(raw)) return false;
        return ObdParser.hasDtcResponse(raw, mode, isCan) || ObdParser.isNoData(raw);
    }

    private Integer optionalWord(int pid, String label, InspectionCheck.Input in) throws IOException {
        String raw = query(String.format(Locale.US, "01%02X", pid), 2500);
        Integer v = raw == null ? null : InspectionCheck.word(ObdParser.mode01Data(raw, pid, 2));
        if (v == null) in.unavailable.add(label);
        return v;
    }

    private Integer optionalByte(int pid, String label, InspectionCheck.Input in) throws IOException {
        String raw = query(String.format(Locale.US, "01%02X", pid), 2500);
        Integer v = raw == null ? null : InspectionCheck.byteValue(ObdParser.mode01Data(raw, pid, 1));
        if (v == null) in.unavailable.add(label);
        return v;
    }

    private String query(String cmd, int timeoutMs) throws IOException {
        return queries.query(cmd, timeoutMs);
    }
}
