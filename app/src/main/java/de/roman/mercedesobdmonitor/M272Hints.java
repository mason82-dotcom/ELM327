package de.roman.mercedesobdmonitor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Werkstatthinweise für den Mercedes M272 (C350 W204). Keine Diagnose, sondern
 * typische Ursachen als Prüfreihenfolge. Zylinderzuordnung M272:
 * Bank 1 = Zylinder 1–3, Bank 2 = Zylinder 4–6.
 */
public final class M272Hints {
    private static final Map<String, String> HINTS = new LinkedHashMap<>();

    static {
        String cam = "M272: Nockenwellenversteller-Magnete und Sensoren zuerst prüfen (Öl im Stecker/"
                + "Kabelbaum ist ein bekanntes M272-Thema). Bleibt der Fehler, Steuertrieb prüfen: "
                + "Kettenlängung, Spann- und Gleitschienen; bei frühen M272 das Ausgleichswellenrad. "
                + "W204 (ab 2007) liegen meist nach der Serienänderung – Motornummer gegenprüfen.";
        for (String c : new String[] {"P0016", "P0017", "P0018", "P0019"}) HINTS.put(c, cam);

        String vvt = "M272: Nockenwellenversteller-Magnet der betroffenen Bank (Öl/Schmutz, "
                + "Stecker auf Ölwanderung prüfen), Ölstand/-qualität, danach Nockenwellenversteller.";
        for (String c : new String[] {"P0010", "P0011", "P0012", "P0013", "P0014",
                "P0020", "P0021", "P0022", "P0023", "P0024"}) HINTS.put(c, vvt);

        String flaps = "M272: Saugrohr-Umschaltklappen/Drallklappen – Kunststoffhebel bzw. Gelenk am "
                + "Saugrohr gebrochen oder ausgehängt, Stellmotor/Unterdruckdose, Unterdruckleitungen. "
                + "Typisches M272-Problem; Saugrohr optisch prüfen.";
        for (String c : new String[] {"P2004", "P2005", "P2006", "P2007"}) HINTS.put(c, flaps);

        String leanBoth = "Beide Bänke mager → gemeinsame Ursache vor der Bankaufteilung: Falschluft über "
                + "Kurbelgehäuseentlüftung (Schläuche/Ölabscheider), Saugrohrdichtungen, "
                + "Luftmassenmesser, Kraftstoffdruck. Fuel-Trim-Test (Leerlauf vs. 2500 U/min) nutzen.";
        HINTS.put("P0171", "Nur Bank 1 (Zyl. 1–3): Einspritzventil/Dichtung, Lambdasonde oder "
                + "Falschluft dieser Bank. Mit P0174 zusammen: " + leanBoth);
        HINTS.put("P0174", "Nur Bank 2 (Zyl. 4–6): Einspritzventil/Dichtung, Lambdasonde oder "
                + "Falschluft dieser Bank. Mit P0171 zusammen: " + leanBoth);
        HINTS.put("P2187", "Mager im Leerlauf Bank 1 – typisch Falschluft (Kurbelgehäuseentlüftung, "
                + "Saugrohr). Bei höherer Drehzahl verschwindend spricht für Falschluft.");
        HINTS.put("P2189", "Mager im Leerlauf Bank 2 – typisch Falschluft (Kurbelgehäuseentlüftung, "
                + "Saugrohr). Bei höherer Drehzahl verschwindend spricht für Falschluft.");

        String misfire = "M272: häufig Zündspule oder Zündkerze; Tauschtest der Zündspule mit einem "
                + "Nachbarzylinder. Danach Einspritzventil und Kompression. Mode-06-Zähler (Aussetzer-"
                + "Button) zeigen den Zylinder auch ohne bestätigten Code.";
        for (int cyl = 1; cyl <= 6; cyl++) {
            HINTS.put("P030" + cyl, "Zylinder " + cyl + " (Bank " + Mode06.m272Bank(cyl) + "). " + misfire);
        }
        HINTS.put("P0300", "Mehrere Zylinder: gemeinsame Ursache (Gemisch/Falschluft, Kraftstoffdruck, "
                + "Kurbelwellensensor) vor Einzelteilen prüfen. Mode-06-Zähler zeigen die Verteilung.");

        HINTS.put("P0128", "Kühlmitteltemperatur zu niedrig – beim M272 meist Thermostat "
                + "(bleibt offen). Warmlauf im Livewert beobachten: 80 °C sollten zügig erreicht werden.");

        String sa = "Sekundärluftsystem (falls verbaut): Sekundärluftpumpe, Umschalt-/Rückschlagventil, "
                + "Schläuche; Rückschlagventil kann durch Kondensat zusetzen.";
        for (String c : new String[] {"P0410", "P0411", "P0412", "P2440", "P2441", "P2442",
                "P2443", "P2444", "P2445"}) HINTS.put(c, sa);

        String cat = "Katalysator erst bewerten, wenn Aussetzer und Lambdasonden (hinten) ausgeschlossen "
                + "sind; Aussetzer schädigen den Kat. Mode 06 enthält die Kat-Testwerte.";
        HINTS.put("P0420", "Bank 1. " + cat);
        HINTS.put("P0430", "Bank 2. " + cat);

        String evap = "Tankentlüftung: Tankdeckel/Dichtung, Regenerierventil, Schläuche zum Aktivkohlefilter.";
        for (String c : new String[] {"P0440", "P0441", "P0442", "P0455", "P0456"}) HINTS.put(c, evap);

        String sensors = "Kurbel-/Nockenwellensensor: Stecker auf Öl und Korrosion prüfen, Signal/Luftspalt; "
                + "beim M272 wandert Öl über die Versteller-Magnete in den Kabelbaum.";
        for (String c : new String[] {"P0335", "P0336", "P0340", "P0341", "P0345", "P0346",
                "P0365", "P0366", "P0390", "P0391"}) HINTS.put(c, sensors);

        HINTS.put("P0101", "Luftmassenmesser Plausibilität: Falschluft nach dem LMM oder verschmutzter "
                + "LMM. MAF im Leerlauf (warm) grob 3,5–5 g/s beim 3,5-l-V6 als Richtwert.");
    }

    private M272Hints() { }

    /** @return Hinweis oder null */
    public static String hint(String code) {
        return code == null ? null : HINTS.get(code);
    }

    /** Muster über mehrere Codes hinweg (z. B. P0171 + P0174). */
    public static List<String> patterns(List<String> codes) {
        List<String> out = new ArrayList<>();
        if (codes.contains("P0171") && codes.contains("P0174")) {
            out.add("P0171 + P0174: beide Bänke mager → Ursache vor der Bankaufteilung "
                    + "(Falschluft Kurbelgehäuseentlüftung/Saugrohr, LMM, Kraftstoffdruck).");
        }
        boolean camB1 = codes.contains("P0016") || codes.contains("P0017");
        boolean camB2 = codes.contains("P0018") || codes.contains("P0019");
        if (camB1 && camB2) {
            out.add("Nockenwellen-Korrelation auf beiden Bänken → gemeinsamer Steuertrieb "
                    + "(Kette/Spanner, bei frühen M272 Ausgleichswelle) wahrscheinlicher als Einzelversteller.");
        }
        int misfireCyl = 0;
        for (int cyl = 1; cyl <= 6; cyl++) if (codes.contains("P030" + cyl)) misfireCyl++;
        if (misfireCyl >= 2) {
            out.add("Aussetzer an mehreren Zylindern → gemeinsame Ursache (Gemisch, Falschluft, "
                    + "Kraftstoffdruck) vor Einzelzündspulen prüfen.");
        }
        return out;
    }
}
