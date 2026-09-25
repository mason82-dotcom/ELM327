package de.roman.mercedesobdmonitor;

import java.util.HashMap;
import java.util.Map;

/** Small offline dictionary for common generic powertrain DTCs; manufacturer-specific codes stay code-only. */
public final class DtcDescriptions {
    private static final Map<String, String> MAP = new HashMap<>();
    static {
        MAP.put("P0016", "Kurbelwelle/Nockenwelle Korrelation – Bank 1 Sensor A");
        MAP.put("P0017", "Kurbelwelle/Nockenwelle Korrelation – Bank 1 Sensor B");
        MAP.put("P0018", "Kurbelwelle/Nockenwelle Korrelation – Bank 2 Sensor A");
        MAP.put("P0019", "Kurbelwelle/Nockenwelle Korrelation – Bank 2 Sensor B");
        MAP.put("P0100", "Luftmassenmesser – Stromkreis");
        MAP.put("P0101", "Luftmassenmesser – Bereich/Funktion");
        MAP.put("P0102", "Luftmassenmesser – Signal zu niedrig");
        MAP.put("P0103", "Luftmassenmesser – Signal zu hoch");
        MAP.put("P0104", "Luftmassenmesser – Signal sporadisch");
        MAP.put("P0171", "Gemisch zu mager – Bank 1");
        MAP.put("P0172", "Gemisch zu fett – Bank 1");
        MAP.put("P0174", "Gemisch zu mager – Bank 2");
        MAP.put("P0175", "Gemisch zu fett – Bank 2");
        MAP.put("P0300", "Zufällige/mehrfache Verbrennungsaussetzer");
        MAP.put("P0301", "Verbrennungsaussetzer Zylinder 1");
        MAP.put("P0302", "Verbrennungsaussetzer Zylinder 2");
        MAP.put("P0303", "Verbrennungsaussetzer Zylinder 3");
        MAP.put("P0304", "Verbrennungsaussetzer Zylinder 4");
        MAP.put("P0305", "Verbrennungsaussetzer Zylinder 5");
        MAP.put("P0306", "Verbrennungsaussetzer Zylinder 6");
        MAP.put("P0420", "Katalysator-Wirkungsgrad unter Schwelle – Bank 1");
        MAP.put("P0430", "Katalysator-Wirkungsgrad unter Schwelle – Bank 2");
    }

    private DtcDescriptions() { }

    public static String describe(String code) {
        String d = MAP.get(code);
        return d == null ? code : code + " – " + d;
    }
}
