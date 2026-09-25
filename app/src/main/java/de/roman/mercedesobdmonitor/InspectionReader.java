package de.roman.mercedesobdmonitor;

import java.io.IOException;
import java.net.SocketTimeoutException;
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
    /** Zugriff auf den Adapter. Implementiert in MainActivity bzw. im Test. */
    public interface Transport {
        String send(String cmd, int timeoutMs) throws IOException;

        /** Wartet nach einem Timeout auf den verspäteten Prompt. true = synchron. */
        boolean resync();
    }

    private final Transport transport;
    private final Boolean isCan;
    /** Ab so vielen resynchronisierten Timeouts in Folge wird abgebrochen (Adapter/Bus gestört). */
    public static final int MAX_CONSECUTIVE_TIMEOUTS = 3;

    private int consecutiveTimeouts;
    private int timeoutCount;

    public InspectionReader(Transport transport, Boolean isCan) {
        this.transport = transport;
        this.isCan = isCan;
    }

    public int timeoutCount() {
        return timeoutCount;
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
        return in;
    }

    /** DTC-Liste nur als bekannt werten, wenn die Antwort gültig oder „NO DATA“ ist. */
    private boolean isValidDtcAnswer(String raw, int mode) {
        if (raw == null) return false;
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

    /**
     * @return Rohantwort oder null bei überbrücktem Timeout
     * @throws IOException wenn der Adapter nicht mehr synchron ist oder die Verbindung weg ist
     */
    private String query(String cmd, int timeoutMs) throws IOException {
        try {
            String raw = transport.send(cmd, timeoutMs);
            consecutiveTimeouts = 0;
            return raw;
        } catch (SocketTimeoutException e) {
            timeoutCount++;
            consecutiveTimeouts++;
            boolean resynced = transport.resync();
            if (!resynced || consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                throw new SocketTimeoutException("Timeout bei " + cmd
                        + (resynced ? " (wiederholt)" : " – Adapter nicht mehr synchron"));
            }
            return null;
        }
    }
}
