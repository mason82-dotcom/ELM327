package de.roman.mercedesobdmonitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Fahrzyklus-Hilfe für offene Readiness-Monitore des ME9.7 (Android-frei, testbar). Grundlage: WIS
 * GF07.61-P-2004CY „Diagnose Fehlerspeicher ME-SFI“ (Fahrzyklus, Readiness-Code, Lampenlogik) und GF07.61-P-3032CY
 * (Katalysator-Aufheizen mit Sekundärluft nach Kaltstart). Die Fahrhinweise je Monitor sind Richtwerte – das
 * Steuergerät prüft nur, wenn seine Bedingungen erfüllt sind, auch über mehrere Fahrten.
 */
public final class ReadinessGuide {
    private ReadinessGuide() { }

    /** Monitore, nach denen das ME9.7 den Readiness-Code setzt (WIS). */
    static final String[] ME97_READINESS = {"Katalysator", "Tankentlüftung (EVAP)", "Sekundärluft", "Lambdasonden",
            "Lambdasondenheizung"};

    /** Fahrhinweis je Monitor (Namen wie in {@link Readiness}); null = kein besonderer Hinweis. */
    static String tip(String monitor) {
        return switch (monitor) {
            case "Katalysator" -> "betriebswarm einige Minuten gleichmäßig 60–100 km/h, danach Gas wegnehmen (Schub); "
                    + "braucht funktionierende Sonden vor und nach Kat";
            case "Sekundärluft" -> "läuft nur direkt nach einem Kaltstart (Kat-Aufheizen): Motor mehrere Stunden "
                    + "abkühlen lassen, starten und die ersten Minuten normal fahren oder im Leerlauf lassen";
            case "Tankentlüftung (EVAP)" -> "nach Kaltstart und Warmlauf längere Teillast- und Leerlaufphasen; "
                    + "nicht direkt nach dem Volltanken";
            case "Lambdasonden", "Lambdasondenheizung" -> "Kaltstart und dann warm gleichmäßige Teillast mit "
                    + "Schubphasen (Gas weg aus 60–80 km/h)";
            case "Verbrennungsaussetzer", "Kraftstoffsystem", "Umfassende Komponenten" -> null; // laufen ständig
            default -> "gemischter Fahrzyklus: Kaltstart, Stadt, gleichmäßige Landstraße 80–100 km/h, Leerlaufphasen";
        };
    }

    /** Abschnitt für den HU/AU-Bericht; null, wenn nichts offen ist und die Lampe aus ist. */
    public static String section(Readiness r, boolean pending) {
        if (r == null) return null;
        List<Readiness.Monitor> open = new ArrayList<>();
        for (Readiness.Monitor m : r.monitors) if (!m.complete) open.add(m);
        if (open.isEmpty() && !r.milOn && !pending) return null;
        StringBuilder sb = new StringBuilder("FAHRZYKLUS-HILFE (ME9.7)\n");
        if (!open.isEmpty()) {
            sb.append("Ein Fahrzyklus des Steuergeräts besteht aus Kaltstart, Stadtfahrt, Überlandfahrt und Abstellen; "
                    + "nicht jede Fahrt erfüllt alle Bedingungen. Offen:\n");
            for (Readiness.Monitor m : open) {
                String tip = tip(m.name);
                sb.append("  • ").append(m.name);
                if (tip != null) sb.append(": ").append(tip);
                sb.append('\n');
            }
            sb.append("Readiness setzt das ME9.7, sobald ").append(String.join(", ", ME97_READINESS))
                    .append(" geprüft sind – das Ergebnis der Prüfung spielt dafür keine Rolle.\n");
        }
        sb.append("Motorkontrollleuchte: geht an, wenn ein abgasrelevanter Fehler in zwei Fahrzyklen nacheinander "
                + "auftritt (blinkt bei katgefährdenden Aussetzern), und aus nach drei fehlerfreien Fahrzyklen. Ein "
                + "nicht bestätigter Fehler verschwindet nach dem nächsten Zyklus; gespeicherte abgasrelevante Fehler "
                + "nach 40, katschädigende nach 80 fehlerfreien Zyklen.");
        return sb.toString();
    }
}
