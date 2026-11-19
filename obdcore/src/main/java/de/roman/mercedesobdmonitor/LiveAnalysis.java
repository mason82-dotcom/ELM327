package de.roman.mercedesobdmonitor;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Live-Auswertung für beide Apps (Android-frei, testbar): hält den letzten gültigen Wert jeder PID, füttert Kat-Tendenz
 * und Trim-Karte und baut den Analyse-Bericht. Vorher lag das nur in der Dashboard-Activity; die Monitor-App zeigt
 * seit 1.3.0 denselben Bericht.
 */
public final class LiveAnalysis {
    private final Map<Integer, Double> samples = new HashMap<>();
    /** Kat-Tendenz je Bank aus der Nachkat-Sonde. */
    public final EngineAnalysis.CatTendency catBank1 = new EngineAnalysis.CatTendency();
    public final EngineAnalysis.CatTendency catBank2 = new EngineAnalysis.CatTendency();
    /** Gesamttrim je Bank und Lastbereich (geregelt, warm). */
    public final EngineAnalysis.TrimMap trimMap = new EngineAnalysis.TrimMap();

    /**
     * Gültigen Messwert übernehmen. Gibt true zurück, wenn es eine STFT-Bank-2-Messung war – dann wurde der Trim-Karte
     * eine Probe angeboten (Status, Last, Tempo und LTFT stammen aus früheren Zyklen; Proben am Schub-Übergang
     * entfallen) und der Aufrufer kann den Trim-Verlauf sichern.
     */
    public boolean onSample(int pid, double value) {
        samples.put(pid, value);
        if (pid == 0x15) catBank1.add(value, fuelStatus(), sample(0x05));
        if (pid == 0x19) catBank2.add(value, fuelStatus(), sample(0x05));
        if (pid != 0x08) return false;
        trimMap.offer(this::sample);
        return true;
    }

    /** Wert ungültig (NO DATA, Fehler): nicht weiter verwenden. */
    public void onInvalid(int pid) {
        samples.remove(pid);
    }

    /** Werte verwerfen, z. B. solange die Oberfläche nichts empfängt; Trim-Karte und Kat-Tendenz bleiben. */
    public void clearSamples() {
        samples.clear();
    }

    /** Neue Verbindung, möglicherweise ein anderes Fahrzeug: Kat-Tendenz und Trim-Karte beginnen neu. */
    public void resetConnection() {
        catBank1.reset();
        catBank2.reset();
        trimMap.reset();
    }

    public Double sample(int pid) {
        return samples.get(pid);
    }

    public Integer fuelStatus() {
        Double v = samples.get(0x03);
        return v == null ? null : (int) Math.round(v);
    }

    /** Breitband-λ einer Bank: Spannungstyp (24/28) zuerst, Stromtyp (34/38) als Ersatz. */
    public Double lambdaBank(int bank) {
        Double v = sample(bank == 1 ? 0x24 : 0x28);
        return v != null ? v : sample(bank == 1 ? 0x34 : 0x38);
    }

    private static String num(Double v, String fmt) {
        return v == null ? LiveValues.INVALID : String.format(Locale.GERMANY, fmt, v);
    }

    /** Analyse-Bericht (Abschnitte in Großbuchstaben, Werte eingerückt); Verlauf darf null sein. */
    public String report(TrimHistory history, ZoneId zone) {
        Double rpm = sample(0x0C), speed = sample(0x0D), maf = sample(0x10), lambdaCmd = sample(0x44);
        Integer status = fuelStatus();
        Double lph = EngineAnalysis.fuelRateLitersPerHour(maf, lambdaCmd, status, sample(0x45), rpm);
        Double l100 = EngineAnalysis.fuelPer100Km(lph, speed);
        Double eta = EngineAnalysis.volumetricEfficiency(maf, sample(0x0B), sample(0x0F), rpm);
        Double dev1 = EngineAnalysis.lambdaDeviation(lambdaBank(1), lambdaCmd, status);
        Double dev2 = EngineAnalysis.lambdaDeviation(lambdaBank(2), lambdaCmd, status);
        Double pedal = EngineAnalysis.pedalRatio(sample(0x49), sample(0x4A));
        Double track = EngineAnalysis.throttleTracking(sample(0x4C), sample(0x45));
        StringBuilder sb = new StringBuilder();
        sb.append("VERBRAUCH (aus Luftmasse)\n  ").append(num(lph, "%.1f l/h"));
        if (l100 != null) sb.append(" · ").append(num(l100, "%.1f l/100 km"));
        sb.append("\n  Annahme Dichte 0,745 kg/l; Schubabschaltung (λ-Soll FFFF oder Klappe zu) = 0\n\n");
        sb.append("LUFTMASSE / LIEFERGRAD\n  ").append(num(eta == null ? null : eta * 100, "%.0f %%"))
                .append(" – ").append(EngineAnalysis.volumetricVerdict(eta)).append("\n\n");
        sb.append("KRAFTSTOFFSYSTEM\n  ").append(status == null ? LiveValues.INVALID : ObdPid.fuelSystemText(status)).append("\n\n");
        sb.append("LAMBDA-REGELUNG\n  Bank 1 Δλ ").append(num(dev1, "%.3f")).append(" · Bank 2 Δλ ").append(num(dev2, "%.3f"))
                .append("\n  ").append(EngineAnalysis.lambdaVerdict(dev1, dev2)).append("\n\n");
        sb.append("TRIM-KARTE (STFT+LTFT, geregelt und warm)\n  ").append(trimMap.table().replace("\n", "\n  "))
                .append("\n  ").append(trimMap.verdict()).append("\n\n");
        if (history != null) {
            sb.append("TRIM-VERLAUF (je Verbindung, neueste zuerst; Leerlauf/Teillast/hohe Last)\n  ")
                    .append(history.table(zone, 5).replace("\n", "\n  "))
                    .append("\n  ").append(history.verdict()).append("\n\n");
        }
        sb.append("KAT-TENDENZ (Sonde nach Kat)\n  Bank 1: ").append(catBank1.verdict())
                .append("\n  Bank 2: ").append(catBank2.verdict()).append("\n\n");
        sb.append("ZÜNDWINKEL\n  ").append(num(sample(0x0E), "%.1f ° v. OT"))
                .append(" – nur bei gleicher Drehzahl/Last vergleichbar, kein Klopfen je Zylinder\n\n");
        sb.append("E-GAS\n  Pedalgeber D/E ").append(num(pedal, "%.2f")).append(" · Drosselklappe Ist−Soll ")
                .append(num(track, "%+.1f %%")).append("\n  stationär > 5 % Abweichung: Drosselklappe prüfen (Richtwert)\n\n");
        sb.append("BORDNETZ\n  ").append(num(sample(0x42), "%.2f V")).append(" – ")
                .append(EngineAnalysis.voltageVerdict(sample(0x42), rpm));
        return sb.toString();
    }
}
