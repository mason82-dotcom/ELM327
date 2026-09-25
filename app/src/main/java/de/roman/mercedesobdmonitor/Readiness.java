package de.roman.mercedesobdmonitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Mode 01 PID 01 – Monitorstatus seit dem letzten Löschen der Fehlercodes (SAE J1979).
 *
 * A: Bit 7 = MIL an, Bit 0–6 = Anzahl bestätigter DTCs
 * B: Bit 3 = Kompressionszündung; Bit 0–2 Verfügbarkeit, Bit 4–6 "nicht abgeschlossen"
 *    für Aussetzer / Kraftstoffsystem / Komponenten
 * C: Verfügbarkeit der motorspezifischen Monitore, D: "nicht abgeschlossen" (1 = offen)
 *
 * Antworten mehrere ECUs (z. B. Motor + Getriebe), werden sie zusammengeführt:
 * MIL/Unterstützung per ODER, DTC-Anzahl als Summe, "offen", sobald ein ECU offen meldet.
 */
public final class Readiness {
    public static final class Monitor {
        public final String name;
        public final boolean complete;

        Monitor(String name, boolean complete) {
            this.name = name;
            this.complete = complete;
        }
    }

    public final boolean milOn;
    public final int dtcCount;
    public final boolean compressionIgnition;
    public final List<Monitor> monitors;
    public final int ecuCount;

    private Readiness(boolean milOn, int dtcCount, boolean compressionIgnition,
                      List<Monitor> monitors, int ecuCount) {
        this.milOn = milOn;
        this.dtcCount = dtcCount;
        this.compressionIgnition = compressionIgnition;
        this.monitors = monitors;
        this.ecuCount = ecuCount;
    }

    private static final String[] CONTINUOUS = {
            "Verbrennungsaussetzer", "Kraftstoffsystem", "Umfassende Komponenten"
    };
    private static final String[] SPARK = {
            "Katalysator", "Beheizter Katalysator", "Tankentlüftung (EVAP)", "Sekundärluft",
            "Klimaanlage (Kältemittel)", "Lambdasonden", "Lambdasondenheizung", "Abgasrückführung (AGR)"
    };
    private static final String[] COMPRESSION = {
            "NMHC-Katalysator", "NOx-Nachbehandlung", "Reserviert", "Ladedruck",
            "Reserviert", "Abgassensor", "Partikelfilter", "AGR/VVT"
    };

    /** @return null, wenn keine gültige 41 01-Antwort enthalten ist */
    public static Readiness parse(String raw) {
        if (raw == null) return null;
        int ecus = 0;
        boolean mil = false;
        boolean ci = false;
        int count = 0;
        int bAvail = 0, bIncomplete = 0, cAvail = 0, dIncomplete = 0;

        for (String msg : frames(raw)) {
            if (!msg.startsWith("4101") || msg.length() < 4 + 8) continue;
            try {
                int a = Integer.parseInt(msg.substring(4, 6), 16);
                int b = Integer.parseInt(msg.substring(6, 8), 16);
                int c = Integer.parseInt(msg.substring(8, 10), 16);
                int d = Integer.parseInt(msg.substring(10, 12), 16);
                ecus++;
                mil |= (a & 0x80) != 0;
                count += a & 0x7F;
                ci |= (b & 0x08) != 0;
                bAvail |= b & 0x07;
                bIncomplete |= (b >> 4) & 0x07;
                cAvail |= c;
                // Nur für unterstützte Monitore zählt "offen".
                dIncomplete |= d & c;
            } catch (RuntimeException ignored) { }
        }
        if (ecus == 0) return null;

        List<Monitor> list = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            if ((bAvail & (1 << i)) != 0) {
                list.add(new Monitor(CONTINUOUS[i], (bIncomplete & (1 << i)) == 0));
            }
        }
        String[] names = ci ? COMPRESSION : SPARK;
        for (int i = 0; i < 8; i++) {
            if ((cAvail & (1 << i)) != 0) {
                list.add(new Monitor(names[i], (dIncomplete & (1 << i)) == 0));
            }
        }
        return new Readiness(mil, count, ci, list, ecus);
    }

    public int incompleteCount() {
        int n = 0;
        for (Monitor m : monitors) if (!m.complete) n++;
        return n;
    }

    private static List<String> frames(String raw) {
        // CAN-Single-Frames und Legacy-Zeilen: je Zeile eine Antwort.
        return ObdParser.canMessages(raw);
    }
}
