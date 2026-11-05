package de.roman.mercedesobdmonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Mode 06 (On-Board Monitoring Test Results) im CAN-Format nach ISO 15031-5.
 *
 * Antwort: 46 + Datensätze à 9 Byte: [OBDMID][TID][UASID][Wert 2][Min 2][Max 2].
 * Aussetzer pro Zylinder: OBDMID A2 (Zyl. 1) … AD (Zyl. 12);
 * TID 0B = EWMA über die letzten 10 Fahrzyklen, TID 0C = Aussetzer letzter/aktueller Zyklus.
 * UASID 24 = Zählwert, 1 Aussetzer/Bit.
 */
public final class Mode06 {
    public static final int MID_SUPPORT_A0 = 0xA0;
    public static final int MID_MISFIRE_CYL1 = 0xA2;
    public static final int TID_EWMA = 0x0B;
    public static final int TID_CURRENT_CYCLE = 0x0C;
    /** SAE J1979 Unit/Scaling-ID 0x24 = Zähler (1 count/bit). Nur so sind Aussetzerwerte eindeutig. */
    public static final int UASID_COUNTS = 0x24;

    public static final class TestResult {
        public final int mid;
        public final int tid;
        public final int uasid;
        public final int value;
        public final int min;
        public final int max;

        TestResult(int mid, int tid, int uasid, int value, int min, int max) {
            this.mid = mid;
            this.tid = tid;
            this.uasid = uasid;
            this.value = value;
            this.min = min;
            this.max = max;
        }
    }

    public static final class Misfire {
        public final int cylinder;
        public final Integer ewma;
        public final Integer currentCycle;

        Misfire(int cylinder, Integer ewma, Integer currentCycle) {
            this.cylinder = cylinder;
            this.ewma = ewma;
            this.currentCycle = currentCycle;
        }
    }

    private Mode06() { }

    public static String command(int mid) {
        return String.format(Locale.US, "06%02X", mid & 0xFF);
    }

    /** Unterstützte OBDMIDs aus einer Bitmap-Antwort (z. B. 06A0 → A1…C0). */
    public static Set<Integer> supportedMids(String raw, int baseMid) {
        Set<Integer> out = new TreeSet<>();
        String marker = String.format(Locale.US, "46%02X", baseMid & 0xFF);
        long union = 0;
        for (String msg : ObdParser.canMessages(raw)) {
            if (!msg.startsWith(marker) || msg.length() < marker.length() + 8) continue;
            try {
                union |= Long.parseLong(msg.substring(marker.length(), marker.length() + 8), 16);
            } catch (RuntimeException ignored) { }
        }
        for (int bit = 0; bit < 32; bit++) {
            if ((union & (1L << (31 - bit))) != 0) out.add(baseMid + bit + 1);
        }
        return out;
    }

    /** Alle Testergebnisse einer Antwort; bei mehreren ECUs werden alle Nachrichten gelesen. */
    public static List<TestResult> results(String raw) {
        List<TestResult> out = new ArrayList<>();
        for (String msg : ObdParser.canMessages(raw)) {
            if (!msg.startsWith("46")) continue;
            String body = msg.substring(2);
            for (int i = 0; i + 18 <= body.length(); i += 18) {
                try {
                    int mid = hex(body, i);
                    int tid = hex(body, i + 2);
                    int uas = hex(body, i + 4);
                    int val = (hex(body, i + 6) << 8) | hex(body, i + 8);
                    int min = (hex(body, i + 10) << 8) | hex(body, i + 12);
                    int max = (hex(body, i + 14) << 8) | hex(body, i + 16);
                    out.add(new TestResult(mid, tid, uas, val, min, max));
                } catch (RuntimeException ignored) { }
            }
        }
        return out;
    }

    /** Aussetzerdaten eines Zylinders aus der Antwort auf 06A(1+Zyl.). null = nicht enthalten. */
    public static Misfire misfire(String raw, int cylinder) {
        int mid = MID_MISFIRE_CYL1 + cylinder - 1;
        Integer ewma = null, current = null;
        for (TestResult r : results(raw)) {
            if (r.mid != mid || r.uasid != UASID_COUNTS) continue;
            if (r.tid == TID_EWMA) ewma = r.value;
            else if (r.tid == TID_CURRENT_CYCLE) current = r.value;
        }
        if (ewma == null && current == null) return null;
        return new Misfire(cylinder, ewma, current);
    }

    /** M272: Bank 1 = Zylinder 1–3 (rechts in Fahrtrichtung), Bank 2 = Zylinder 4–6. */
    public static int m272Bank(int cylinder) {
        return cylinder <= 3 ? 1 : 2;
    }

    public static String misfireReport(List<Misfire> list) {
        StringBuilder sb = new StringBuilder("Aussetzer pro Zylinder (Mode 06)\n");
        sb.append("EWMA = gleitender Mittelwert über 10 Fahrzyklen · Zyklus = letzter/aktueller Fahrzyklus\n\n");
        if (list.isEmpty()) {
            sb.append("Das Motorsteuergerät liefert keine zylinderbezogenen Aussetzerdaten (OBDMID A2–A7).");
            return sb.toString();
        }
        int total = 0;
        int worst = -1;
        int worstCount = 0;
        int[] bank = new int[3];
        for (Misfire m : list) {
            int cur = m.currentCycle == null ? 0 : m.currentCycle;
            int ew = m.ewma == null ? 0 : m.ewma;
            total += cur;
            bank[m272Bank(m.cylinder)] += cur;
            int score = Math.max(cur, ew);
            if (score > worstCount) {
                worstCount = score;
                worst = m.cylinder;
            }
            sb.append(String.format(Locale.GERMANY, "Zyl. %d (Bank %d): EWMA %s · Zyklus %s%n",
                    m.cylinder, m272Bank(m.cylinder),
                    m.ewma == null ? "–" : m.ewma.toString(),
                    m.currentCycle == null ? "–" : m.currentCycle.toString()));
        }
        sb.append('\n');
        if (worstCount == 0) {
            sb.append("Bewertung: keine Aussetzer gezählt.");
        } else {
            sb.append(String.format(Locale.GERMANY,
                    "Bewertung: %d Aussetzer im Zyklus (Bank 1: %d · Bank 2: %d); auffälligster Zylinder %d.%n",
                    total, bank[1], bank[2], worst));
            sb.append("Einzelner Zylinder auffällig → Zündspule/Zündkerze/Einspritzventil dieses Zylinders "
                    + "(Tauschtest der Zündspule mit Nachbarzylinder). Eine ganze Bank auffällig → eher "
                    + "Gemisch/Falschluft dieser Bank. Werte sind relativ zu vergleichen; die Grenzwerte "
                    + "des Steuergeräts gelten für bestätigte DTCs.");
        }
        return sb.toString();
    }

    private static int hex(String s, int i) {
        return Integer.parseInt(s.substring(i, i + 2), 16);
    }
}
