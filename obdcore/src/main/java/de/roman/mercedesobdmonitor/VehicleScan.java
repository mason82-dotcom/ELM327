package de.roman.mercedesobdmonitor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fahrzeugprofil: welche Standard-Daten jedes Steuergerät unterstützt (Android-frei, testbar).
 * Gelesen werden nur die Unterstützungs-Bitmaps 01 00/20/…/A0, 06 00/20/…/E0 und 09 00 – mit Headern (ATH1),
 * damit Motor (7E8) und Getriebe (7E9) getrennt erscheinen. Die Rohbitmaps stehen im Bericht, damit sie
 * am echten Fahrzeug dokumentiert werden können (Recherche: kein öffentlicher M272-Dump).
 */
public final class VehicleScan {
    private VehicleScan() { }

    /** Antwortservice + Basis-PID + 4 Byte Bitmap, mit optionalem 11-bit-Header und PCI-Längenbyte davor. */
    private static Pattern bitmapPattern(int responseService, int base) {
        return Pattern.compile(String.format(Locale.US,
                "^(?:(7E[8-9A-F]|[0-9A-F]{3})[0-9A-F]{2})?%02X%02X([0-9A-F]{8})", responseService, base));
    }

    /**
     * Unterstützte Nummern je Steuergerät aus einer Bitmap-Antwort.
     * @param responseService 0x41 (Mode 01), 0x46 (Mode 06) oder 0x49 (Mode 09)
     * @return Header → unterstützte PIDs/MIDs/Infotypen (Header „?“, wenn der Adapter keine Header liefert)
     */
    public static Map<String, TreeSet<Integer>> supportedByEcu(String raw, int responseService, int base) {
        Map<String, TreeSet<Integer>> out = new TreeMap<>();
        if (raw == null) return out;
        Pattern p = bitmapPattern(responseService, base);
        for (String line : raw.toUpperCase(Locale.US).split("[\\r\\n>]+")) {
            String hex = line.replaceAll("[^0-9A-F]", "");
            if (hex.isEmpty()) continue;
            Matcher m = p.matcher(hex);
            if (!m.find()) continue;
            String ecu = m.group(1) == null ? "?" : m.group(1);
            long mask = Long.parseLong(m.group(2), 16);
            TreeSet<Integer> set = out.computeIfAbsent(ecu, k -> new TreeSet<>());
            for (int bit = 0; bit < 32; bit++) {
                if ((mask & (1L << (31 - bit))) != 0) set.add(base + bit + 1);
            }
        }
        return out;
    }

    /** Ob irgendein Steuergerät den nächsten Bitmap-Block (base + 0x20) meldet. */
    public static boolean anyHasNext(Map<String, TreeSet<Integer>> part, int base) {
        for (TreeSet<Integer> s : part.values()) if (s.contains(base + 0x20)) return true;
        return false;
    }

    /** Ergebnis eines Scans: je Steuergerät die unterstützten Nummern je Mode, dazu die Rohantworten. */
    public static final class Result {
        public final Map<String, TreeSet<Integer>> mode01 = new TreeMap<>();
        public final Map<String, TreeSet<Integer>> mode06 = new TreeMap<>();
        public final Map<String, TreeSet<Integer>> mode09 = new TreeMap<>();
        public final Map<String, String> raw = new LinkedHashMap<>();

        public void merge(Map<String, TreeSet<Integer>> target, Map<String, TreeSet<Integer>> part) {
            for (Map.Entry<String, TreeSet<Integer>> e : part.entrySet())
                target.computeIfAbsent(e.getKey(), k -> new TreeSet<>()).addAll(e.getValue());
        }
    }

    public static String ecuName(String header) {
        return switch (header) {
            case "7E8" -> "Motor (7E8)";
            case "7E9" -> "Getriebe (7E9)";
            case "?" -> "Steuergerät (Adapter liefert keine Absender)";
            default -> "Steuergerät " + header;
        };
    }

    private static final Map<Integer, String> PID_NAMES = new LinkedHashMap<>();
    static {
        for (ObdPid p : ObdPid.defaultPids()) PID_NAMES.put(p.pid, p.label);
        PID_NAMES.put(0x01, "Monitorstatus/MIL");
        PID_NAMES.put(0x13, "Lage der Lambdasonden");
        PID_NAMES.put(0x1C, "OBD-Norm");
        PID_NAMES.put(0x21, "Strecke mit MIL");
        PID_NAMES.put(0x2E, "Tankentlüftung Soll");
        PID_NAMES.put(0x2F, "Tankfüllstand");
        PID_NAMES.put(0x30, "Warmläufe seit Löschen");
        PID_NAMES.put(0x31, "Strecke seit Löschen");
        PID_NAMES.put(0x41, "Monitorstatus aktueller Zyklus");
        PID_NAMES.put(0x4D, "Zeit mit MIL");
        PID_NAMES.put(0x4E, "Zeit seit Löschen");
        PID_NAMES.put(0x51, "Kraftstoffart");
        PID_NAMES.put(0x5C, "Öltemperatur");
    }

    private static final Map<Integer, String> INFOTYPES = Map.of(
            0x02, "FIN", 0x04, "Kalibrierungs-ID", 0x06, "Prüfsumme CVN", 0x08, "IUPR-Zähler", 0x0A, "Steuergerätename");

    private static boolean isBitmapEntry(int n) {
        return n % 0x20 == 0;
    }

    private static String list(TreeSet<Integer> set, Map<Integer, String> names, boolean mid) {
        List<String> parts = new ArrayList<>();
        for (int n : set) {
            if (isBitmapEntry(n)) continue;
            String name = mid ? Mode06Overview.midName(n) : names.get(n);
            parts.add(String.format(Locale.US, "%02X", n) + (name == null ? "" : " " + name));
        }
        return parts.isEmpty() ? "–" : String.join(", ", parts);
    }

    public static String report(Result r) {
        StringBuilder sb = new StringBuilder();
        TreeSet<String> ecus = new TreeSet<>();
        ecus.addAll(r.mode01.keySet());
        ecus.addAll(r.mode06.keySet());
        ecus.addAll(r.mode09.keySet());
        if (ecus.isEmpty()) return "Kein Steuergerät hat Unterstützungs-Bitmaps geliefert (Zündung an? Adapter verbunden?).";
        for (String ecu : ecus) {
            sb.append(ecuName(ecu)).append('\n');
            TreeSet<Integer> m1 = r.mode01.getOrDefault(ecu, new TreeSet<>());
            sb.append("  Live-Daten (Mode 01): ").append(list(m1, PID_NAMES, false)).append('\n');
            TreeSet<Integer> m6 = r.mode06.getOrDefault(ecu, new TreeSet<>());
            sb.append("  Monitortests (Mode 06): ").append(list(m6, null, true)).append('\n');
            TreeSet<Integer> m9 = r.mode09.getOrDefault(ecu, new TreeSet<>());
            sb.append("  Fahrzeuginfos (Mode 09): ").append(list(m9, INFOTYPES, false)).append("\n\n");
        }
        TreeSet<Integer> engine = r.mode01.getOrDefault("7E8", r.mode01.getOrDefault("?", new TreeSet<>()));
        sb.append("Lambdasonden vorn: ");
        if (engine.contains(0x24) || engine.contains(0x28) || engine.contains(0x34) || engine.contains(0x38)) sb.append("Breitband");
        else if (engine.contains(0x14) || engine.contains(0x18)) sb.append("Sprungsonde");
        else sb.append("nicht gemeldet");
        sb.append(" · Nachkat-Sonde: ").append(engine.contains(0x15) || engine.contains(0x19) ? "ja (Kat-Tendenz möglich)" : "nicht gemeldet");
        sb.append("\n\nRohantworten (zur Dokumentation des Fahrzeugs):\n");
        for (Map.Entry<String, String> e : r.raw.entrySet()) {
            sb.append(e.getKey()).append(" → ").append(e.getValue().replaceAll("[\\r\\n>]+", " ").trim()).append('\n');
        }
        return sb.toString().trim();
    }
}
