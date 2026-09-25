package de.roman.mercedesobdmonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ObdPid {
    public enum Formula {
        RPM, SPEED, TEMP, PERCENT, MAP, MAF, FUEL_TRIM, VOLTAGE, EQUIV_RATIO
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
        int need = switch (formula) {
            case RPM, MAF, VOLTAGE, EQUIV_RATIO -> 2;
            default -> 1;
        };
        return decode(ObdParser.mode01Data(raw, pid, need));
    }

    /** Freeze-Frame-Wert (Mode 02, Frame 00) mit identischer Formel wie Mode 01. */
    public Double parseFreezeFrame(String raw) {
        return decode(ObdParser.mode02Data(raw, pid, bytesNeeded()));
    }

    private int bytesNeeded() {
        return switch (formula) {
            case RPM, MAF, VOLTAGE, EQUIV_RATIO -> 2;
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
        };
    }

    public String format(double value) {
        return String.format(Locale.GERMANY, "% ." + decimals + "f %s", value, unit).trim();
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
        return p;
    }
}
