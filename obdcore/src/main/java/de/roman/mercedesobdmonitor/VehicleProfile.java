package de.roman.mercedesobdmonitor;

/**
 * Fahrzeugprofil (Android-frei, testbar). Ein Profil ändert nur Darstellung und Plausibilitätsprüfungen:
 * es fügt nie einen Befehl hinzu, erzwingt kein ELM-Protokoll (ATSP0 bleibt) und schaltet nie einen
 * Schreibdienst frei. Werte wie im OpenNextion-Projekt (firmware/ObdDisplay/VehicleProfile.h).
 */
public final class VehicleProfile {
    /** Vollständige Bezeichnung (INFO-Seite, CSV-Kopf). */
    public final String name;
    /** Kurzname für die Kopfzeile. */
    public final String shortName;
    public final int cylinders;
    public final int banks;
    /** Letzter Zylinder von Bank 1 (M272: Bank 1 = Zyl. 1–3, Bank 2 = Zyl. 4–6). */
    public final int bank1Last;
    /** Obergrenze der Drehzahlskala. */
    public final int chartMaxRpm;
    /** Plausibles Leerlauffenster für den KOER-Hinweis (bewusst weiter als der warme Leerlauf). */
    public final int idleMinRpm;
    public final int idleMaxRpm;
    /** Erwarteter Bus; wird gegen ATDPN geprüft, nie erzwungen. */
    public final boolean expectCan;
    /** M272-spezifische Werkstatthinweise zu Fehlercodes anzeigen. */
    public final boolean m272Hints;

    public VehicleProfile(String name, String shortName, int cylinders, int banks, int bank1Last, int chartMaxRpm,
                          int idleMinRpm, int idleMaxRpm, boolean expectCan, boolean m272Hints) {
        if (cylinders < 1 || cylinders > 6) throw new IllegalArgumentException("Mode-06-Allowlist deckt nur Zylinder 1–6 ab");
        if (banks != 1 && banks != 2) throw new IllegalArgumentException("1 oder 2 Bänke");
        if (bank1Last < 1 || bank1Last > cylinders) throw new IllegalArgumentException("Bankaufteilung außerhalb der Zylinderzahl");
        if (!(idleMinRpm < idleMaxRpm && idleMaxRpm < chartMaxRpm)) throw new IllegalArgumentException("Drehzahlfenster");
        this.name = name;
        this.shortName = shortName;
        this.cylinders = cylinders;
        this.banks = banks;
        this.bank1Last = bank1Last;
        this.chartMaxRpm = chartMaxRpm;
        this.idleMinRpm = idleMinRpm;
        this.idleMaxRpm = idleMaxRpm;
        this.expectCan = expectCan;
        this.m272Hints = m272Hints;
    }

    /**
     * Mercedes-Benz C350 T-Modell (S204, Kombi der Baureihe 204), M272 V6 3,5 l, Baujahr 2008.
     * Bank 1 = Zylinder 1–3, Bank 2 = Zylinder 4–6. Warmer Leerlauf etwa 600–700 U/min; das Fenster
     * 550–1000 ist bewusst weiter (Kaltstart, Klimalast). OBD über CAN (ISO 15765-4).
     */
    public static final VehicleProfile C350_S204_M272_2008 = new VehicleProfile(
            "Mercedes-Benz C350 T-Modell (S204), M272 V6 3,5 l, Baujahr 2008",
            "C350 S204 · M272", 6, 2, 3, 7000, 550, 1000, true, true);

    public static final VehicleProfile GENERIC = new VehicleProfile(
            "Generisch OBD-II", "OBD-II", 6, 2, 3, 7000, 500, 1200, false, false);

    /** Aktives Profil beider Apps. */
    public static final VehicleProfile ACTIVE = C350_S204_M272_2008;

    public int bankOf(int cylinder) {
        return cylinder <= bank1Last ? 1 : 2;
    }

    public boolean idlePlausible(double rpm) {
        return rpm >= idleMinRpm && rpm <= idleMaxRpm;
    }

    /** Erkannter Bus gegen Erwartung; {@code isCan == null} = noch unbekannt. */
    public String busCheck(Boolean isCan) {
        if (isCan == null) return "Bus: noch nicht erkannt";
        if (!expectCan) return "Bus: " + (isCan ? "CAN" : "kein CAN") + " (keine Erwartung im Profil)";
        return isCan ? "Bus: CAN wie erwartet (ISO 15765-4)"
                : "Bus: kein CAN erkannt – für einen C350 S204 (2008) ungewöhnlich, Adapter/Protokoll prüfen";
    }

    /** Kurzer Text zum Leerlauf; nur sinnvoll bei laufendem Motor und Stillstand. */
    public String idleText(double rpm, double speedKmh) {
        if (Double.isNaN(rpm) || Double.isNaN(speedKmh) || speedKmh >= 3 || rpm < 300) return "";
        return idlePlausible(rpm)
                ? "Leerlauf plausibel (" + idleMinRpm + "–" + idleMaxRpm + " U/min)"
                : "Leerlauf außerhalb " + idleMinRpm + "–" + idleMaxRpm + " U/min – Kaltstart/Last oder prüfen";
    }
}
