package de.roman.mercedesobdmonitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Starter- und Batterietest aus der Adapterspannung (ATRV, OBD-Stecker Pin 16 = Dauerplus).
 *
 * Ablauf: Zündung an, Motor aus → {@link Phase#REST} misst die Ruhespannung; {@link Phase#WAIT_CRANK} wartet auf
 * den Startvorgang (Einbruch um {@link #CRANK_DROP} gegenüber den letzten 2 s); {@link Phase#CRANK} verfolgt den
 * tiefsten Wert und die Anlassdauer; {@link Phase#CHARGE} misst danach die Ladespannung. Rein auswertend und
 * Android-frei: die Sitzung liefert die Proben ({@link #offer}), es wird nichts angesteuert.
 *
 * Grenzen sind Richtwerte (Bleiakku 12 V, BCI-Lasttest-Tabelle für die Mindestspannung), keine Mercedes-Angaben.
 * Nicht thread-safe; gehört dem Thread, der die Proben liest.
 */
public final class StarterTest {
    public enum Phase { REST, WAIT_CRANK, CRANK, CHARGE, DONE, FAILED }

    public enum Rating { GOOD, FAIR, WEAK, UNKNOWN }

    /** Messdauer der Ruhespannung ab der ersten gültigen Probe. */
    static final long REST_MS = 3000;
    static final int REST_MIN_SAMPLES = 5;
    /** So lange wird auf den Startvorgang gewartet. */
    static final long WAIT_MS = 30_000;
    /** Bezug für den Einbruch: Median der letzten 2 s vor dem Anlassen. */
    static final long REF_WINDOW_MS = 2000;
    /** Einbruch, ab dem ein Anlassen erkannt wird (ATRV-Rauschen der Klone ±0,1 V). */
    static final double CRANK_DROP = 0.8;
    /** Anlassen beendet, sobald die Spannung zweimal hintereinander höchstens so weit unter dem Bezug liegt. */
    static final double CRANK_END = 0.3;
    /**
     * Anlassen ebenfalls beendet, wenn die Spannung so lange über der Erkennungsschwelle bleibt: der Motor läuft,
     * erreicht aber den Wert vor dem Start nicht (Lichtmaschine lädt nicht). Sonst liefe CRANK bis
     * {@link #MAX_CRANK_MS} und der Bericht spräche von sehr langem Anlassen statt von der Ladung.
     */
    static final long RECOVER_MS = 1500;
    /** Längeres Anlassen wird nicht weiter verfolgt. */
    static final long MAX_CRANK_MS = 20_000;
    /**
     * Messdauer nach dem Anlassen; die Ladespannung ist der Median der letzten {@link #CHARGE_TAIL_MS}. Das ME fährt die
     * Lichtmaschine nach dem Start verzögert hoch (WIS GF07.61-P-3003CY); am 21.11.2026 lag der Median der zweiten
     * Hälfte von 10 s bei 11,1 V, der Höchstwert bei 13,5 V.
     */
    static final long CHARGE_MS = 20_000;
    static final long CHARGE_TAIL_MS = 5_000;
    /** Ohne gültige Probe so lange: Test abbrechen. */
    static final long MAX_SILENCE_MS = 3000;
    /** Über dieser Ruhespannung läuft der Motor schon oder ein Ladegerät hängt dran. */
    static final double REST_MAX = 13.2;
    /** Darunter ist die Probe kein Bordnetzwert (Adapter misst Unsinn). */
    static final double PLAUSIBLE_MIN = 5.0;
    /** Beim Anlassen gilt ein tieferer Wert noch als Messung: gerade ein sehr tiefer Einbruch ist der Befund. */
    static final double CRANK_PLAUSIBLE_MIN = 3.0;
    static final double PLAUSIBLE_MAX = 18.0;

    private Phase phase = Phase.REST;
    private final double ambientC;
    private String failure;

    private long restStart = -1;
    private final List<Double> restSamples = new ArrayList<>();
    private double rest = Double.NaN;

    private long waitStart;
    /** Proben der letzten {@link #REF_WINDOW_MS} vor dem Anlassen: {Zeit, Spannung}. */
    private final ArrayDeque<double[]> window = new ArrayDeque<>();

    private double preCrank = Double.NaN;
    private long crankStart = -1;
    private long crankEnd = -1;
    private long pendingEnd = -1;
    private long recoverSince = -1;
    /** true: Abbruch durch Verbindungsverlust (nicht durch den Benutzer). */
    private boolean linkLost;
    private double crankMin = Double.NaN;
    private int crankSamples;
    /** true = Motor sprang an, ohne dass ein Einbruch gemessen wurde (Einbruch kürzer als ein Abtastschritt). */
    private boolean crankMissed;
    private boolean crankCut;

    private long chargeStart = -1;
    private final List<double[]> chargeSamples = new ArrayList<>();
    private double chargeMax = Double.NaN;

    private long lastValid = -1;
    private long first = -1;
    private long last = -1;
    private int samples;
    private int invalidSamples;

    /** @param ambientC Außentemperatur (01 46) in °C oder NaN; verschiebt die Mindestspannung beim Anlassen */
    public StarterTest(double ambientC) {
        this.ambientC = ambientC;
    }

    public Phase phase() {
        return phase;
    }

    public boolean finished() {
        return phase == Phase.DONE || phase == Phase.FAILED;
    }

    /**
     * Eine Probe verarbeiten.
     * @param volts Spannung oder NaN, wenn die Antwort nicht lesbar war
     */
    public Phase offer(long t, double volts) {
        if (finished()) return phase;
        if (first < 0) first = t;
        last = t;
        double min = phase == Phase.WAIT_CRANK || phase == Phase.CRANK ? CRANK_PLAUSIBLE_MIN : PLAUSIBLE_MIN;
        boolean valid = !Double.isNaN(volts) && volts >= min && volts <= PLAUSIBLE_MAX;
        if (!valid) {
            invalidSamples++;
            long since = lastValid >= 0 ? lastValid : first;
            if (t - since >= MAX_SILENCE_MS) fail("Keine lesbare Spannung vom Adapter seit "
                    + seconds(t - since) + " s.");
            return phase;
        }
        samples++;
        lastValid = t;
        switch (phase) {
            case REST -> rest(t, volts);
            case WAIT_CRANK -> waitCrank(t, volts);
            case CRANK -> crank(t, volts);
            case CHARGE -> charge(t, volts);
            default -> { }
        }
        return phase;
    }

    /**
     * Zeitschritt ohne neue Probe (z. B. Antwort ausgeblieben): prüft nur die Zeitgrenzen.
     */
    public Phase tick(long t) {
        if (finished()) return phase;
        long since = lastValid >= 0 ? lastValid : (first >= 0 ? first : t);
        if (first < 0) first = t;
        last = Math.max(last, t);
        if (t - since >= MAX_SILENCE_MS) fail("Keine lesbare Spannung vom Adapter seit " + seconds(t - since) + " s.");
        else if (phase == Phase.WAIT_CRANK && t - waitStart >= WAIT_MS) failNoCrank();
        return phase;
    }

    private void rest(long t, double v) {
        if (restStart < 0) restStart = t;
        restSamples.add(v);
        if (t - restStart < REST_MS || restSamples.size() < REST_MIN_SAMPLES) return;
        rest = median(restSamples);
        if (Collections.min(restSamples) <= rest - CRANK_DROP) {
            fail("Spannungseinbruch schon während der Ruhemessung – zu früh gestartet? Motor aus, Zündung an, "
                    + "Test neu starten und erst bei „JETZT MOTOR STARTEN“ anlassen.");
            rest = Double.NaN;
            return;
        }
        if (rest > REST_MAX) {
            fail(String.format(Locale.GERMANY, "Ruhespannung %.2f V: über %.1f V läuft der Motor schon oder ein "
                    + "Ladegerät ist angeschlossen. Motor aus, Zündung an und neu starten.", rest, REST_MAX));
            return;
        }
        phase = Phase.WAIT_CRANK;
        waitStart = t;
        window.clear();
        window.add(new double[] {t, v});
    }

    private void waitCrank(long t, double v) {
        if (t - waitStart >= WAIT_MS) {
            failNoCrank();
            return;
        }
        double ref = reference();
        if (v <= ref - CRANK_DROP) {
            preCrank = ref;
            crankStart = t;
            crankMin = v;
            crankSamples = 1;
            phase = Phase.CRANK;
            return;
        }
        if (v >= rest + CRANK_DROP) {
            // Motor läuft (Ladespannung), der Einbruch fiel zwischen zwei Proben
            crankMissed = true;
            preCrank = ref;
            startCharge(t, v);
            return;
        }
        window.add(new double[] {t, v});
        while (!window.isEmpty() && t - (long) window.peekFirst()[0] > REF_WINDOW_MS) window.pollFirst();
    }

    private void crank(long t, double v) {
        if (v < crankMin) crankMin = v;
        if (v > preCrank - CRANK_DROP) {
            if (recoverSince < 0) recoverSince = t;
            else if (t - recoverSince >= RECOVER_MS) {
                crankEnd = pendingEnd >= 0 ? pendingEnd : recoverSince;
                startCharge(t, v);
                return;
            }
        } else {
            recoverSince = -1;
        }
        if (v >= preCrank - CRANK_END) {
            if (pendingEnd < 0) {
                pendingEnd = t;
                return;
            }
            crankEnd = pendingEnd;
            startCharge(t, v);
            return;
        }
        pendingEnd = -1;
        crankSamples++;
        if (t - crankStart >= MAX_CRANK_MS) {
            crankEnd = t;
            crankCut = true;
            startCharge(t, v);
        }
    }

    private void startCharge(long t, double v) {
        phase = Phase.CHARGE;
        chargeStart = t;
        chargeSamples.clear();
        chargeSamples.add(new double[] {t, v});
        chargeMax = v;
    }

    private void charge(long t, double v) {
        chargeSamples.add(new double[] {t, v});
        if (v > chargeMax) chargeMax = v;
        if (t - chargeStart >= CHARGE_MS) phase = Phase.DONE;
    }

    private double reference() {
        List<Double> vs = new ArrayList<>();
        for (double[] s : window) vs.add(s[1]);
        return vs.isEmpty() ? rest : median(vs);
    }

    private void failNoCrank() {
        fail("Kein Startvorgang innerhalb von " + (WAIT_MS / 1000) + " s erkannt (kein Einbruch um "
                + fmt1(CRANK_DROP) + " V).");
    }

    private void fail(String reason) {
        failure = reason;
        phase = Phase.FAILED;
    }

    /**
     * Abbruch von außen. Während des Anlassens ist ein Verbindungsabbruch ({@code linkLost}) selbst ein Befund:
     * viele WLAN-Adapter setzen bei Unterspannung zurück. Ein Abbruch durch den Benutzer ist keiner.
     */
    public void abort(String reason, boolean linkLost) {
        if (finished()) return;
        this.linkLost = linkLost;
        fail(reason);
    }

    /** Riss die Verbindung ab, während gerade angelassen wurde? */
    public boolean abortedWhileCranking() {
        return phase == Phase.FAILED && linkLost && crankStart >= 0 && chargeStart < 0;
    }

    // ---------------------------------------------------------------- Kennwerte

    public double rest() {
        return rest;
    }

    public double crankMin() {
        return crankMin;
    }

    /** Anlassdauer in ms oder -1. */
    public long crankMs() {
        return crankStart >= 0 && crankEnd >= 0 ? crankEnd - crankStart : -1;
    }

    /** Ladespannung: Median der letzten {@link #CHARGE_TAIL_MS} der Messung (Lichtmaschine fährt verzögert hoch). */
    public double charge() {
        if (chargeSamples.isEmpty()) return Double.NaN;
        long from = chargeStart + CHARGE_MS - CHARGE_TAIL_MS;
        List<Double> vs = new ArrayList<>();
        for (double[] s : chargeSamples) if ((long) s[0] >= from) vs.add(s[1]);
        if (vs.isEmpty()) for (double[] s : chargeSamples) vs.add(s[1]);
        return median(vs);
    }

    /**
     * Gehaltener Höchstwert der Ladespannung: höchster Median über ein Fenster von einer Sekunde – eine einzelne
     * Spitze direkt nach dem Anlassen zählt nicht, eine mehrere Sekunden anliegende Ladespannung schon (Review).
     */
    double sustainedChargeMax() {
        double best = Double.NaN;
        for (int i = 0; i < chargeSamples.size(); i++) {
            double start = chargeSamples.get(i)[0];
            if (start + 1000 > chargeSamples.get(chargeSamples.size() - 1)[0]) break;
            List<Double> window = new ArrayList<>();
            for (int j = i; j < chargeSamples.size() && chargeSamples.get(j)[0] < start + 1000; j++) {
                window.add(chargeSamples.get(j)[1]);
            }
            double m = median(window);
            if (Double.isNaN(best) || m > best) best = m;
        }
        return best;
    }

    /** Mittlerer Abstand zweier Proben in ms oder -1. */
    public long sampleIntervalMs() {
        int n = samples + invalidSamples;
        return n > 1 ? (last - first) / (n - 1) : -1;
    }

    public String failure() {
        return failure;
    }

    /** Mindestspannung beim Anlassen nach Temperatur (BCI-Lasttest-Tabelle, Batterietemperatur ≈ Außentemperatur). */
    public static double crankLimit(double tempC) {
        if (Double.isNaN(tempC) || tempC >= 21) return 9.6;
        if (tempC >= 16) return 9.5;
        if (tempC >= 10) return 9.4;
        if (tempC >= 4) return 9.3;
        if (tempC >= -1) return 9.1;
        if (tempC >= -7) return 8.9;
        if (tempC >= -12) return 8.7;
        return 8.5;
    }

    /** Unter dieser Ruhespannung ist der Mercedes-Batterietest nicht aussagekräftig (AR54.10-P-1129-01A). */
    public static final double MB_TEST_MIN_REST = 12.4;
    /** Unter etwa dieser Spannung startet das ME den Motor nicht mehr (WIS GF07.61-P-2003V). */
    public static final double ME_START_MIN = 7.0;

    public static Rating rateRest(double v) {
        if (Double.isNaN(v)) return Rating.UNKNOWN;
        if (v >= MB_TEST_MIN_REST) return Rating.GOOD;
        if (v >= 12.2) return Rating.FAIR;
        return Rating.WEAK;
    }

    public static Rating rateCrank(double min, double tempC) {
        if (Double.isNaN(min)) return Rating.UNKNOWN;
        double limit = crankLimit(tempC);
        if (min >= limit + 0.4) return Rating.GOOD;
        if (min >= limit) return Rating.FAIR;
        return Rating.WEAK;
    }

    /**
     * Ladespannung kurz nach dem Start. Das ME regelt die Lichtmaschine (WIS GF07.61-P-3003CY): Obergrenze bis 15 V,
     * bei gestörter Schnittstelle fest 13,45–14,15 V; nach dem Anlassen lädt es die Batterie nach, eine Absenkung
     * gibt es erst bei voller Batterie im Leerlauf.
     */
    public static Rating rateCharge(double v) {
        if (Double.isNaN(v)) return Rating.UNKNOWN;
        if (v >= 13.5 && v <= EngineAnalysis.CHARGE_MAX_NORMAL) return Rating.GOOD;
        if (v >= 13.2 && v <= 15.2) return Rating.FAIR;
        return Rating.WEAK;
    }

    /** ATRV-Antwort ("12.4V", "12,4 V", mit Echo/Prompt) als Zahl; NaN, wenn keine Spannung darin steht. */
    public static double parseVolts(String raw) {
        if (raw == null) return Double.NaN;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2}[.,]\\d{1,2})\\s*V").matcher(raw.toUpperCase(Locale.US));
        if (!m.find()) return Double.NaN;
        try {
            return Double.parseDouble(m.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // ---------------------------------------------------------------- Texte

    /** Kurzer Status für die Oberfläche. */
    public String status(long now) {
        return switch (phase) {
            case REST -> restStart < 0 ? "Starttest: Ruhespannung messen … Motor noch nicht starten"
                    : "Starttest: Ruhespannung messen … " + seconds(Math.max(0, REST_MS - (now - restStart)))
                    + " s · Motor noch nicht starten";
            case WAIT_CRANK -> "Starttest: JETZT MOTOR STARTEN · Ruhe " + fmtV(rest) + " V · noch "
                    + seconds(Math.max(0, WAIT_MS - (now - waitStart))) + " s";
            case CRANK -> "Starttest: Anlassen … tiefster Wert " + fmtV(crankMin) + " V";
            case CHARGE -> "Starttest: Ladespannung messen … " + seconds(Math.max(0, CHARGE_MS - (now - chargeStart)))
                    + " s · Motor laufen lassen";
            case DONE -> "Starttest: fertig";
            case FAILED -> "Starttest: abgebrochen";
        };
    }

    /**
     * Bericht.
     * @param rpmAfter Drehzahl nach dem Test (01 0C) oder NaN, wenn nicht gelesen
     */
    public String report(double rpmAfter) {
        StringBuilder b = new StringBuilder();
        if (phase == Phase.FAILED) {
            b.append("Abgebrochen: ").append(failure).append('\n');
            if (abortedWhileCranking()) {
                b.append("Die Verbindung riss beim Anlassen ab. Viele WLAN-Adapter setzen unter etwa 8–9 V zurück – "
                        + "das spricht für einen starken Spannungseinbruch (Batterie, Klemmen, Massekabel).\n");
            }
            if (Double.isNaN(rest) && crankStart < 0) {
                b.append("\nAblauf: Zündung an, Motor aus, Test starten, nach der Ruhemessung den Motor normal starten.");
                return b.toString().trim();
            }
            b.append('\n');
        }
        double temp = ambientC;
        Rating rRest = rateRest(rest);
        Rating rCrank = crankMissed ? Rating.UNKNOWN : rateCrank(crankMin, temp);
        double charge = charge();
        Rating rCharge = rateCharge(charge);

        if (!Double.isNaN(rest)) {
            b.append("Ruhespannung (Zündung an, Motor aus): ").append(fmtV(rest)).append(" V – ")
                    .append(restText(rRest)).append('\n');
        }
        if (crankMissed) {
            b.append("Anlassen: Einbruch nicht erfasst (kürzer als ein Abtastschritt von ~")
                    .append(sampleIntervalMs()).append(" ms) – Motor lief danach.\n");
        } else if (crankStart >= 0) {
            double limit = crankLimit(temp);
            b.append("Beim Anlassen: tiefster Wert ").append(fmtV(crankMin)).append(" V (Einbruch ")
                    .append(fmtV(preCrank - crankMin)).append(" V) – ").append(crankText(rCrank)).append('\n');
            if (!Double.isNaN(crankMin) && crankMin < ME_START_MIN + 1.0) {
                b.append("  Nahe der Startgrenze: unter etwa ").append(fmt1(ME_START_MIN))
                        .append(" V startet das Motorsteuergerät den Motor nicht mehr (WIS).\n");
            }
            b.append("  Richtwert ≥ ").append(fmt1(limit)).append(" V ")
                    .append(Double.isNaN(temp) ? "(21 °C angenommen, keine Außentemperatur)"
                            : "bei " + Math.round(temp) + " °C Außentemperatur").append('\n');
            long ms = crankMs();
            if (ms >= 0) {
                b.append("Anlassdauer: ").append(String.format(Locale.GERMANY, "%.1f", ms / 1000.0)).append(" s")
                        .append(crankCut ? " (nach " + (MAX_CRANK_MS / 1000) + " s nicht weiter verfolgt)" : "")
                        .append(" – ").append(durationText(ms)).append('\n');
            }
        }
        if (!Double.isNaN(charge)) {
            b.append("Ladespannung nach dem Start: ").append(fmtV(charge)).append(" V (höchster Wert ")
                    .append(fmtV(chargeMax)).append(" V) – ").append(chargeText(charge, rCharge)).append('\n');
        }
        if (!Double.isNaN(rpmAfter)) {
            b.append("Motor danach: ").append(Math.round(rpmAfter)).append(" U/min (01 0C)").append('\n');
        } else if (phase == Phase.DONE) {
            b.append("Motor danach: Drehzahl nicht gelesen").append('\n');
        }

        String verdict = verdict(rRest, rCrank, rCharge, rpmAfter);
        if (verdict != null) b.append('\n').append(verdict).append('\n');

        long iv = sampleIntervalMs();
        b.append("\nHinweise: ATRV misst am OBD-Stecker (Pin 16) mit etwa ±0,2 V Toleranz");
        if (iv > 0) b.append(", Abtastung alle ~").append(iv).append(" ms – die kurze Einschaltspitze kann tiefer liegen");
        b.append(". Nach einer Fahrt liegt die Ruhespannung durch Oberflächenladung höher; aussagekräftig ist ein Start "
                + "nach mehreren Stunden Standzeit. Die Lichtmaschine regelt das Motorsteuergerät (Schnittstelle, "
                + "WIS GF07.61-P-3003CY): bis 15 V sind normal, bei voller Batterie senkt es die Ladespannung im "
                + "Leerlauf deutlich ab – ein Wert knapp über 13 V kann dann gewollt sein; mit eingeschalteten "
                + "Verbrauchern wiederholen. Herstellerwerte: Startgrenze ~" + fmt1(ME_START_MIN) + " V, Batterietest ab "
                + fmt1(MB_TEST_MIN_REST) + " V Ruhespannung, Ladung bis " + fmt1(EngineAnalysis.CHARGE_MAX_NORMAL)
                + " V; die übrigen Schwellen sind Richtwerte für 12-V-Bleibatterien.");
        return b.toString().trim();
    }

    private String verdict(Rating rest, Rating crank, Rating charge, double rpmAfter) {
        if (phase != Phase.DONE) return null;
        List<String> issues = new ArrayList<>();
        if (rest == Rating.WEAK && crank == Rating.WEAK) {
            issues.add("Batterie entladen oder verschlissen: erst vollständig laden, dann den Test wiederholen – auch "
                    + "der Werkstatt-Batterietest braucht über " + fmt1(MB_TEST_MIN_REST) + " V Ruhespannung.");
        } else if (crank == Rating.WEAK) {
            issues.add("Ruhespannung in Ordnung, aber starker Einbruch: Batterie mit wenig Kapazität (Kaltstartstrom) "
                    + "oder Übergangswiderstand – Batterieklemmen, Massebänder, Anlasserkabel prüfen.");
        } else if (rest == Rating.WEAK) {
            issues.add("Ruhespannung niedrig: Batterie nachladen (Ruhestromverbraucher? Kurzstrecken?) – der "
                    + "Werkstatt-Batterietest braucht über " + fmt1(MB_TEST_MIN_REST) + " V.");
        }

        if (charge == Rating.WEAK) {
            double c = charge();
            issues.add(c > 15.2 ? "Ladespannung zu hoch: Regler der Lichtmaschine prüfen lassen."
                    : !Double.isNaN(rpmAfter) && rpmAfter < 300
                    ? "Motor läuft nicht – keine Aussage zur Ladespannung."
                    // Eine tief entladene Batterie zieht die Spannung im Leerlauf herunter; steigt sie zwischendurch auf
                    // Ladeniveau, lädt die Lichtmaschine – dann erst nach einigen Minuten bei 2000 U/min urteilen.
                    : rest == Rating.WEAK && sustainedChargeMax() >= 13.2
                    ? "Ladespannung im Leerlauf niedrig, zwischendurch aber " + fmtV(sustainedChargeMax()) + " V – bei tief "
                    + "entladener Batterie üblich (sie nimmt viel Strom auf). Nach einigen Minuten bei etwa 2000 U/min "
                    + "die Steuergerät-Spannung lesen: 13,5–15 V heißt, die Lichtmaschine lädt."
                    : "Lichtmaschine lädt nicht ausreichend: Riemen, Regler, Ladekabel prüfen.");
        }
        if (issues.isEmpty()) {
            boolean allGood = rest != Rating.FAIR && crank != Rating.FAIR && charge != Rating.FAIR;
            return allGood ? "Gesamt: Batterie, Anlassen und Ladung unauffällig."
                    : "Gesamt: unauffällig, einzelne Werte knapp – bei Kälte im Blick behalten.";
        }
        return "Gesamt: " + String.join(" ", issues);
    }

    private static String restText(Rating r) {
        return switch (r) {
            case GOOD -> "gut geladen";
            case FAIR -> "teilgeladen";
            case WEAK -> "niedrig (entladen)";
            default -> "–";
        };
    }

    private static String crankText(Rating r) {
        return switch (r) {
            case GOOD -> "gut";
            case FAIR -> "knapp";
            case WEAK -> "zu tief";
            default -> "–";
        };
    }

    private static String durationText(long ms) {
        if (ms <= 2000) return "normal";
        if (ms <= 4000) return "lang";
        return "sehr lang – bei guter Spannung eher Kraftstoff/Zündung als Batterie";
    }

    private static String chargeText(double v, Rating r) {
        return switch (r) {
            case GOOD -> "Lichtmaschine lädt";
            case FAIR -> v > EngineAnalysis.CHARGE_MAX_NORMAL ? "etwas hoch" : "knapp (viele Verbraucher an?)";
            case WEAK -> v > 15.2 ? "zu hoch" : "lädt nicht ausreichend";
            default -> "–";
        };
    }

    static double median(List<Double> values) {
        List<Double> s = new ArrayList<>(values);
        Collections.sort(s);
        int n = s.size();
        if (n == 0) return Double.NaN;
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    private static long seconds(long ms) {
        return (ms + 999) / 1000;
    }

    private static String fmt1(double v) {
        return String.format(Locale.GERMANY, "%.1f", v);
    }

    private static String fmtV(double v) {
        return Double.isNaN(v) ? "–" : String.format(Locale.GERMANY, "%.1f", v);
    }
}
