package de.roman.mercedesobdmonitor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Werkstatthinweise für den Mercedes M272 (C350 S204, T-Modell). Keine Diagnose, sondern
 * typische Ursachen als Prüfreihenfolge. Zylinderzuordnung M272:
 * Bank 1 = Zylinder 1–3 = rechte Zylinderbank (Lambdasonden G3/4 vor, G3/6 nach Kat),
 * Bank 2 = Zylinder 4–6 = linke Zylinderbank (G3/3, G3/5) – laut ME9.7-Fehlertabelle (P0030 = G3/4, P0016 = rechts).
 */
public final class M272Hints {
    private static final Map<String, String> HINTS = new LinkedHashMap<>();

    static {
        String cam = "M272: Nockenwellenversteller-Magnete und Sensoren zuerst prüfen (Öl im Stecker/"
                + "Kabelbaum ist ein bekanntes M272-Thema). Test laut MBUSA-DTB S-B-03.30/08i (2009): Code löschen, "
                + "Motor neu starten – kommt er sofort wieder, ist es die Lage der Nockenwellen zur Kurbelwelle, also "
                + "der Steuertrieb (Kettenlängung, Spanner, Gleitschienen). Das verschlissene Ausgleichswellenrad betrifft "
                + "nur M272 bis Motornummer 2729..30 468993; Baureihe 204 (W204/S204) nimmt das Bulletin ausdrücklich aus.";
        for (String c : new String[] {"P0016", "P0017", "P0018", "P0019"}) HINTS.put(c, cam);

        String vvt = "M272: Nockenwellenversteller-Magnet der betroffenen Bank (Öl/Schmutz, "
                + "Stecker auf Ölwanderung prüfen), Ölstand/-qualität, danach Nockenwellenversteller. Laut MBUSA-DTB "
                + "S-B-03.30/08i haben M272 bis Motornummer 2729..30 759427 – Baureihe 204 eingeschlossen – noch die "
                + "alten Magnete (Ersatz A272 051 01 77); Ölverlust am Magnet ohne Fehlercode bis 2729..30 895140.";
        for (String c : new String[] {"P0010", "P0011", "P0012", "P0013", "P0014",
                "P0020", "P0021", "P0022", "P0023", "P0024"}) HINTS.put(c, vvt);

        String flaps = "M272: Saugrohr-Umschaltklappen/Drallklappen – Kunststoffhebel bzw. Gelenk am "
                + "Saugrohr gebrochen oder ausgehängt, Stellmotor/Unterdruckdose, Unterdruckleitungen. "
                + "Typisches M272-Problem; Saugrohr optisch prüfen.";
        for (String c : new String[] {"P2004", "P2005", "P2006", "P2007"}) HINTS.put(c, flaps);

        String leanBoth = "Beide Bänke mager → gemeinsame Ursache vor der Bankaufteilung: Falschluft über "
                + "Kurbelgehäuseentlüftung (beim M272 reißt oft das Y-Stück des Entlüftungsschlauchs unter dem "
                + "Luftmassenmesser – Forenbericht, 100–160 Tkm), Saugrohrdichtungen, "
                + "Luftmassenmesser und dessen Sitz am Ansaugkrümmer (Schelle), Kraftstoffdruck, Regenerierventil. "
                + "Fuel-Trim-Test (Leerlauf vs. 2500 U/min) nutzen.";
        HINTS.put("P0171", "Nur Bank 1 (Zyl. 1–3, rechte Seite, Sonde G3/4): Gemischadaption im Leerlauf oder in der "
                + "Teillast an der Grenze. Einspritzventile Zyl. 1–3 (Dichtheit/Strahlbild), Falschluft oder Abgasleck "
                + "rechts vor G3/4, Sonde G3/4. Mit P0174 zusammen: " + leanBoth);
        HINTS.put("P0174", "Nur Bank 2 (Zyl. 4–6, linke Seite, Sonde G3/3): Gemischadaption im Leerlauf oder in der "
                + "Teillast an der Grenze. Einspritzventile Zyl. 4–6, Falschluft oder Abgasleck links vor G3/3, Sonde "
                + "G3/3. Mit P0171 zusammen: " + leanBoth);
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
        // Das ME9.7 meldet Kat-Fehler als P0422 (rechts und links); P0420/P0430 kommen dort laut Tabelle nicht vor.
        HINTS.put("P0420", "Bank 1 – beim ME9.7 nicht in der Fehlertabelle (dort P0422); Code ggf. von anderem "
                + "Steuergerät oder Tester. " + cat);
        HINTS.put("P0430", "Bank 2 – beim ME9.7 nicht in der Fehlertabelle (dort P0422); Code ggf. von anderem "
                + "Steuergerät oder Tester. " + cat);

        String evap = "Tankentlüftung: Tankdeckel/Dichtung, Regenerierventil, Schläuche zum Aktivkohlefilter.";
        for (String c : new String[] {"P0440", "P0441", "P0442", "P0455", "P0456"}) HINTS.put(c, evap);

        String sensors = "Kurbel-/Nockenwellensensor: Stecker auf Öl und Korrosion prüfen, Signal/Luftspalt; "
                + "beim M272 wandert Öl über die Versteller-Magnete in den Kabelbaum.";
        for (String c : new String[] {"P0335", "P0336", "P0340", "P0341", "P0345", "P0346",
                "P0365", "P0366", "P0390", "P0391"}) HINTS.put(c, sensors);

        HINTS.put("P0101", "Luftmassenmesser Plausibilität: Falschluft nach dem LMM oder verschmutzter "
                + "LMM. MAF im Leerlauf (warm) grob 3,5–5 g/s beim 3,5-l-V6 als Richtwert.");

        // Prüfreihenfolgen je Gruppe; Seiten und Bauteile laut ME9.7-Fehlertabelle (Me97Codes).
        String heater = "Sondenheizung: zuerst Stecker und Leitung der genannten Sonde (Korrosion, Scheuerstelle am "
                + "Abgasstrang), dann Heizwiderstand der Sonde messen lassen; ist die Heizung defekt, Sonde tauschen.";
        for (String c : new String[] {"P0030", "P0031", "P0032", "P0036", "P0037", "P0038", "P0050", "P0051",
                "P0052", "P0056", "P0057", "P0058", "P0135", "P0141", "P0155"}) HINTS.put(c, heater);
        String front = "Sonde vor Kat: Stecker/Leitung, dann Abgasleck vor der Sonde (Krümmer, Flansch) – angesaugte "
                + "Luft verfälscht das Signal Richtung mager; erst danach die Sonde selbst.";
        for (String c : new String[] {"P0130", "P0150", "P2237", "P2240", "P2243", "P2247", "P2251", "P2254",
                "P2626", "P2629", "P2414", "P2415", "P2195", "P2196", "P2197", "P2198"}) HINTS.put(c, front);
        String rear = "Sonde nach Kat: Stecker/Leitung; „gealtert“ heißt zu träge – Sonde tauschen, vorher Abgasleck "
                + "zwischen Kat und Sonde ausschließen.";
        for (String c : new String[] {"P0136", "P0138", "P0139", "P0140", "P0156", "P0158", "P0159", "P0160",
                "P2270", "P2271", "P2272", "P2273"}) HINTS.put(c, rear);
        String swapped = "Sondenstecker vertauscht – fast immer nach einer Reparatur: Stecker rechts/links prüfen.";
        HINTS.put("P0040", swapped);
        HINTS.put("P0041", swapped);
        String injector = "Einspritzventil des genannten Zylinders: Stecker und Leitung zum Motorsteuergerät, "
                + "Widerstand des Ventils; bei Kurzschluss nach Plus/Masse zuerst den Kabelbaum.";
        for (String c : new String[] {"P0201", "P0202", "P0203", "P0204", "P0205", "P0206", "P0261", "P0262", "P0264",
                "P0265", "P0267", "P0268", "P0270", "P0271", "P0273", "P0274", "P0276", "P0277"}) HINTS.put(c, injector);
        String coil = "Zündspule des genannten Zylinders: Stecker, dann Tauschtest mit dem Nachbarzylinder – wandert der "
                + "Fehler mit, ist es die Spule; bleibt er, Leitung bzw. Endstufe im Motorsteuergerät.";
        for (String c : new String[] {"P0351", "P0352", "P0353", "P0354", "P0355", "P0356",
                "P2300", "P2301", "P2303", "P2304", "P2306", "P2307", "P2309", "P2310", "P2312", "P2313",
                "P2315", "P2316"}) HINTS.put(c, coil);
        String thermostat = "Thermostat Y110 elektrisch: Stecker und Leitung am Thermostat; ohne Ansteuerung regelt es "
                + "nur noch mechanisch (ab etwa 110 °C ganz offen). Warmlauf-Check zeigt die Folge.";
        for (String c : new String[] {"P0597", "P0598", "P0599"}) HINTS.put(c, thermostat);
        String coolant = "Kühlmitteltemperatursensor B11/4: Stecker, Leitung, Sensor; Livewert mit der Anzeige im "
                + "Kombiinstrument vergleichen. Falsche Werte stören Gemisch und Warmlauf.";
        for (String c : new String[] {"P0116", "P0117", "P0118"}) HINTS.put(c, coolant);
        String maf = "Luftmassenmesser B2/5: Stecker (Wackelkontakt), Sitz am Ansaugkrümmer, Falschluft danach; "
                + "ein verschmutzter LMM misst zu wenig.";
        for (String c : new String[] {"P0068", "P0102", "P0104"}) HINTS.put(c, maf);
        String map = "Drucksensor B28 im Saugrohr: Stecker, Unterdruck-/Saugrohrdichtheit, Sensor.";
        for (String c : new String[] {"P0106", "P0107", "P0108"}) HINTS.put(c, map);
        String throttle = "Drosselklappensteller M16/6: Stecker, Klappe auf Verschmutzung oder Vereisung prüfen; nach "
                + "Reinigung oder Tausch muss die Werkstatt die Klappe anlernen (nur mit Tester).";
        for (String c : new String[] {"P0122", "P0123", "P0222", "P0223", "P0638", "P2072", "P2101", "P2111", "P2112",
                "P2135", "P2176"}) HINTS.put(c, throttle);
        String camSensor = "Nockenwellen-Hallgeber: Stecker auf Öl prüfen (Ölwanderung von den Versteller-Magneten), "
                + "Leitung, Geber.";
        for (String c : new String[] {"P0342", "P0343", "P0347", "P0348", "P0367", "P0368", "P0392", "P0393"}) {
            HINTS.put(c, camSensor);
        }
        String magnet = "Nockenwellenmagnet (Seite siehe ME9.7-Zeile): Stecker auf Öl (bekanntes M272-Thema), Leitung, "
                + "Magnet (Ersatz A272 051 01 77 bei Motoren bis 2729..30 759427).";
        for (String c : new String[] {"P2088", "P2089", "P2090", "P2091", "P2092", "P2093"}) HINTS.put(c, magnet);
        String knock = "Klopfsensor: Stecker und Leitung, Anzugsmoment des Sensors; Klopfregelung gestört kostet "
                + "Leistung, Zündung wird zurückgenommen.";
        for (String c : new String[] {"P0324", "P0325", "P0327", "P0328", "P0330", "P0332", "P0333"}) HINTS.put(c, knock);
        String purge = "Tankentlüftung: Regenerierventil Y58/1 bzw. Absperrventil Y58/4 am Aktivkohlefilter – Stecker, "
                + "Leitung, Ventil klemmt; Schläuche dicht?";
        for (String c : new String[] {"P0444", "P0446", "P0447", "P0448", "P0458", "P0459", "P2421", "P2422"}) {
            HINTS.put(c, purge);
        }
        String saWiring = "Sekundärluft elektrisch: Relais und Umschaltventil Y32 – Stecker, Leitung, Sicherung.";
        for (String c : new String[] {"P0413", "P0414", "P0418", "P2257", "P2258"}) HINTS.put(c, saWiring);
        String supply = "Spannung am Motorsteuergerät: Batterie und Ladespannung prüfen (Starttest, Bordnetz-Warnung), "
                + "Masseverbindungen und Klemmen.";
        for (String c : new String[] {"P0562", "P0563", "P2505"}) HINTS.put(c, supply);
        HINTS.put("P0620", "Lichtmaschine: Ladespannung im Livewert und im Starttest prüfen, Keilrippenriemen, "
                + "Ladekabel; Werkstatt prüft den Ladestrom (120–150 A bei 2000 U/min).");
        String ecu = "Motorsteuergerät meldet einen internen Fehler: zuerst Versorgung und Masse prüfen, Fehler löschen "
                + "lassen und beobachten; ein Tausch ist erst nach Prüfung in der Werkstatt sinnvoll.";
        for (String c : new String[] {"P0604", "P0605", "P0606", "P0607"}) HINTS.put(c, ecu);
        HINTS.put("P0422", "Katalysator (das ME9.7 meldet P0422 für rechts und links, nicht P0420/P0430). " + cat);
        HINTS.put("P2279", "Falschluft im Ansaugtrakt laut Steuergerät. " + leanBoth);
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
