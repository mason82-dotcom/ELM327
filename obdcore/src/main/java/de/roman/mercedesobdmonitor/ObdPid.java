package de.roman.mercedesobdmonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ObdPid {
    public enum Formula {
        RPM, SPEED, TEMP, PERCENT, MAP, MAF, FUEL_TRIM, VOLTAGE, EQUIV_RATIO,
        /** 01 0E: A/2 − 64 °v. OT. */
        TIMING,
        /** 01 14–1B: Sprungsonde, A/200 V (Byte B = Trim der Sonde, hier nicht genutzt). */
        O2_VOLTAGE,
        /** 01 24–2B / 34–3B: Breitbandsonde, λ = (256A+B)·2/65536. */
        WIDEBAND_LAMBDA,
        /** 01 3C–3F: (256A+B)/10 − 40 °C (beim ME9.7 modelliert, nicht gemessen). */
        CAT_TEMP,
        /** 01 43: (256A+B)·100/255 %. */
        ABSOLUTE_LOAD,
        /** 01 1F: 256A+B s. */
        SECONDS,
        /** 01 03: Status des Kraftstoffsystems 1 (Bitwert 1/2/4/8/16). */
        FUEL_SYSTEM
    }

    public final int pid;
    public final String label;
    public final String unit;
    public final int decimals;
    public final Formula formula;

    public ObdPid(int pid, String label, String unit, int decimals, Formula formula) {
        this.pid = pid;
        this.label = label;
        this.unit = unit;
        this.decimals = decimals;
        this.formula = formula;
    }

    public String command() {
        return String.format(Locale.US, "01%02X", pid);
    }

    public Double parse(String raw) {
        return decode(ObdParser.mode01Data(raw, pid, bytesNeeded()));
    }

    /** Freeze-Frame-Wert (Mode 02, Frame 00) mit identischer Formel wie Mode 01. */
    public Double parseFreezeFrame(String raw) {
        return decode(ObdParser.mode02Data(raw, pid, bytesNeeded()));
    }

    private int bytesNeeded() {
        return switch (formula) {
            case RPM, MAF, VOLTAGE, EQUIV_RATIO, O2_VOLTAGE, WIDEBAND_LAMBDA, CAT_TEMP, ABSOLUTE_LOAD, SECONDS -> 2;
            default -> 1;
        };
    }

    private Double decode(byte[] d) {
        int need = bytesNeeded();
        if (d == null || d.length < need) return null;
        int a = d[0] & 0xFF;
        int b = d.length > 1 ? d[1] & 0xFF : 0;
        return switch (formula) {
            case RPM -> ((a * 256.0) + b) / 4.0;
            case SPEED -> (double) a;
            case TEMP -> (double) a - 40.0;
            case PERCENT -> a * 100.0 / 255.0;
            case MAP -> (double) a;
            case MAF -> ((a * 256.0) + b) / 100.0;
            case FUEL_TRIM -> (a - 128.0) * 100.0 / 128.0;
            case VOLTAGE -> ((a * 256.0) + b) / 1000.0;
            case EQUIV_RATIO -> ((a * 256.0) + b) / 32768.0;
            case TIMING -> a / 2.0 - 64.0;
            case O2_VOLTAGE -> a / 200.0;
            case WIDEBAND_LAMBDA -> ((a * 256.0) + b) * 2.0 / 65536.0;
            case CAT_TEMP -> ((a * 256.0) + b) / 10.0 - 40.0;
            case ABSOLUTE_LOAD -> ((a * 256.0) + b) * 100.0 / 255.0;
            case SECONDS -> (a * 256.0) + b;
            case FUEL_SYSTEM -> (double) a;
        };
    }

    public String format(double value) {
        if (formula == Formula.FUEL_SYSTEM) return fuelSystemText((int) value);
        // Rohwert FFFF = Messbereichsende, nicht „λ 2,000“; am C350 bei Schubabschaltung (Log 07.11.2026)
        if (formula == Formula.EQUIV_RATIO && EngineAnalysis.isLambdaSaturated(value)) return "FFFF (Schub/Messbereichsende)";
        if (formula == Formula.WIDEBAND_LAMBDA && EngineAnalysis.isLambdaSaturated(value)) return "> 2 λ (Schub/Messbereich)";
        return String.format(Locale.GERMANY, "% ." + decimals + "f %s", value, unit).trim();
    }

    /** Klartext des Kraftstoffsystem-Status (01 03, SAE J1979). */
    public static String fuelSystemText(int status) {
        return switch (status) {
            case 0 -> "–";
            case 1 -> "offen (kalt)";
            case 2 -> "geregelt (Lambda)";
            case 4 -> "offen (Last/Schub)";
            case 8 -> "offen (Fehler)";
            case 16 -> "geregelt, Sondenfehler";
            default -> "Status " + status;
        };
    }

    /**
     * Kern-PIDs (Monitor seit 1.0): werden auch ohne Unterstützungs-Bitmap gepollt und im Freeze Frame gelesen.
     * Die Analyse-PIDs kommen nur dazu, wenn das Fahrzeug sie meldet.
     */
    public static boolean isCore(int pid) {
        return switch (pid) {
            case 0x0C, 0x0D, 0x05, 0x04, 0x11, 0x10, 0x0B, 0x0F, 0x06, 0x07, 0x08, 0x09, 0x42, 0x44, 0x33 -> true;
            default -> false;
        };
    }

    public static List<ObdPid> defaultPids() {
        List<ObdPid> p = new ArrayList<>();
        p.add(new ObdPid(0x0C, "Motordrehzahl", "U/min", 0, Formula.RPM));
        p.add(new ObdPid(0x0D, "Geschwindigkeit", "km/h", 0, Formula.SPEED));
        p.add(new ObdPid(0x05, "Kühlmittel", "°C", 0, Formula.TEMP));
        p.add(new ObdPid(0x04, "Motorlast", "%", 1, Formula.PERCENT));
        p.add(new ObdPid(0x11, "Drosselklappe", "%", 1, Formula.PERCENT));
        p.add(new ObdPid(0x10, "Luftmasse MAF", "g/s", 2, Formula.MAF));
        p.add(new ObdPid(0x0B, "Saugrohrdruck MAP", "kPa", 0, Formula.MAP));
        p.add(new ObdPid(0x0F, "Ansaugluft", "°C", 0, Formula.TEMP));
        p.add(new ObdPid(0x06, "STFT Bank 1", "%", 1, Formula.FUEL_TRIM));
        p.add(new ObdPid(0x07, "LTFT Bank 1", "%", 1, Formula.FUEL_TRIM));
        p.add(new ObdPid(0x08, "STFT Bank 2", "%", 1, Formula.FUEL_TRIM));
        p.add(new ObdPid(0x09, "LTFT Bank 2", "%", 1, Formula.FUEL_TRIM));
        p.add(new ObdPid(0x42, "Steuergerät-Spannung", "V", 3, Formula.VOLTAGE));
        p.add(new ObdPid(0x44, "Soll-Lambda", "λ", 3, Formula.EQUIV_RATIO));
        p.add(new ObdPid(0x33, "Umgebungsdruck", "kPa", 0, Formula.MAP));
        // Further standard PIDs for analyses (C350/M272 research 07.11.2026). Polled only when the vehicle
        // reports them in its supported-PID bitmap, so unsupported ones cost nothing.
        p.add(new ObdPid(0x03, "Kraftstoffsystem", "", 0, Formula.FUEL_SYSTEM));
        p.add(new ObdPid(0x0E, "Zündwinkel", "° v. OT", 1, Formula.TIMING));
        p.add(new ObdPid(0x43, "Absolute Last", "%", 1, Formula.ABSOLUTE_LOAD));
        p.add(new ObdPid(0x45, "Drosselklappe relativ", "%", 1, Formula.PERCENT));
        p.add(new ObdPid(0x47, "Drosselklappe B", "%", 1, Formula.PERCENT));
        p.add(new ObdPid(0x49, "Fahrpedal D", "%", 1, Formula.PERCENT));
        p.add(new ObdPid(0x4A, "Fahrpedal E", "%", 1, Formula.PERCENT));
        p.add(new ObdPid(0x4C, "Drosselklappe Soll", "%", 1, Formula.PERCENT));
        p.add(new ObdPid(0x46, "Außentemperatur", "°C", 0, Formula.TEMP));
        p.add(new ObdPid(0x1F, "Laufzeit seit Start", "s", 0, Formula.SECONDS));
        p.add(new ObdPid(0x14, "Lambdasonde B1S1", "V", 3, Formula.O2_VOLTAGE));
        p.add(new ObdPid(0x15, "Lambdasonde B1S2 (nach Kat)", "V", 3, Formula.O2_VOLTAGE));
        p.add(new ObdPid(0x18, "Lambdasonde B2S1", "V", 3, Formula.O2_VOLTAGE));
        p.add(new ObdPid(0x19, "Lambdasonde B2S2 (nach Kat)", "V", 3, Formula.O2_VOLTAGE));
        p.add(new ObdPid(0x24, "Lambda B1S1 (Breitband)", "λ", 3, Formula.WIDEBAND_LAMBDA));
        p.add(new ObdPid(0x28, "Lambda B2S1 (Breitband)", "λ", 3, Formula.WIDEBAND_LAMBDA));
        p.add(new ObdPid(0x34, "Lambda B1S1 (Breitband, Strom)", "λ", 3, Formula.WIDEBAND_LAMBDA));
        p.add(new ObdPid(0x38, "Lambda B2S1 (Breitband, Strom)", "λ", 3, Formula.WIDEBAND_LAMBDA));
        p.add(new ObdPid(0x3C, "Kat-Temperatur B1 (modelliert)", "°C", 0, Formula.CAT_TEMP));
        p.add(new ObdPid(0x3D, "Kat-Temperatur B2 (modelliert)", "°C", 0, Formula.CAT_TEMP));
        return p;
    }
}
