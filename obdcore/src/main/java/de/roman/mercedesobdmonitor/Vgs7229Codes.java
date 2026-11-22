package de.roman.mercedesobdmonitor;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Getriebe-Fehlercodes der 7G-Tronic 722.9 (Daimler-Kennungen des vollintegrierten Getriebesteuergeräts VGS, Antwort
 * auf {@code 18 02 FF 00}, ADR-004) – eigene deutsche Kurzfassung der Tabelle „ETC2 (722.9) ab 2008“ im
 * Werkstatthandbuch, je Code Bauteil und Teilfehler, dazu je Gruppe ein Hinweis für den nächsten Schritt.
 * Android-frei.
 *
 * <p>Die Antwort liefert 2 Bytes je Code; nur 4-stellige Kennungen (0218, 2759, C300) gelten als Treffer. Wie die
 * 5-stelligen E-Kennungen der Tabelle (EC100, E0513) in 2 Bytes übertragen werden, ist nicht belegt: Eine E-Kennung
 * mit denselben letzten vier Stellen wird nur als Möglichkeit genannt ({@link #possibleECode}), ohne Rat.
 */
public final class Vgs7229Codes {
    /** Gruppe mit Hinweis „was jetzt“ – ohne Fachjargon, Löschen bleibt in der App gesperrt. */
    public enum Group {
        OIL("Getriebeöl", "Last verringern (Steigung, Anhänger), die ATF-Anzeige im Dashboard beobachten. Ölstand und "
                + "Ölkühler (sitzt im Motorkühler) prüfen lassen."),
        SLIP("Schalten/Kraftübertragung", "Schonend fahren, nicht voll beschleunigen. Ölstand und -zustand prüfen "
                + "lassen – das 722.9 hat keinen Peilstab, die Werkstatt misst bei vorgegebener Öltemperatur."),
        VALVE("Schaltventil", "Das Getriebe geht oft in den Notlauf (fester Gang). Werkstatt: Steckverbindung und "
                + "Leitungssatz im Getriebe prüfen lassen."),
        SPEED("Drehzahlsensor", "Das Getriebe geht oft in den Notlauf. Die Sensoren sitzen im Getriebesteuergerät – "
                + "Werkstatt."),
        SELECTOR("Wählhebel/Wählbereich", "P/R/N/D wird evtl. nicht erkannt, der Start kann gesperrt sein. Werkstatt: "
                + "Sensor prüfen und anlernen lassen."),
        UNIT("Getriebesteuergerät", "Interner Fehler, nicht selbst behebbar. Zuerst die Batterie prüfen (Starttest in "
                + "der App) – Unterspannung kann solche Codes auslösen –, dann Werkstatt mit Mercedes-Tester."),
        VOLTAGE("Spannung", "Batterie und Ladung prüfen (Starttest in der App), danach erneut lesen."),
        CAN("Datenbus (CAN)", "Ein anderes Steuergerät war zeitweise nicht erreichbar – oft nach Unterspannung oder "
                + "abgeklemmter Batterie. Batterie prüfen und erneut lesen; bleibt der Code, Werkstatt."),
        OTHER("Codierung/Software", "Nur in der Werkstatt lösbar (Codierung oder Software)."),
        NONE("Scheinfehler", "Laut Tabelle ohne Bedeutung – kein Handlungsbedarf.");

        public final String title;
        public final String advice;

        Group(String title, String advice) {
            this.title = title;
            this.advice = advice;
        }
    }

    /** Bedeutung eines Codes. */
    public static final class Entry {
        public final String code;
        /** Meist eine Gruppe; E1707 (Übertemperatur und Überspannung) gehört zu zwei. */
        public final Set<Group> groups;
        public final String text;

        Entry(String code, Set<Group> groups, String text) {
            this.code = code;
            this.groups = groups;
            this.text = text;
        }
    }

    private static final Map<String, Entry> M = new HashMap<>();

    private static void put(Group g, String text, String... codes) {
        put(EnumSet.of(g), text, codes);
    }

    private static void put(Set<Group> groups, String text, String... codes) {
        for (String c : codes) {
            if (M.put(c, new Entry(c, groups, text)) != null) throw new IllegalStateException("doppelt: " + c);
        }
    }

    static {
        put(Group.OIL, "Getriebeöl zu heiß – das Getriebe nimmt Motormoment zurück", "0218");
        put(Group.OIL, "Öltemperatursensor (Y3/8s2): Signal unplausibel, Unterspannung", "0712");
        put(Group.OIL, "Öltemperatursensor (Y3/8s2): Signal unplausibel, Überspannung", "0713");
        put(Group.OIL, "Öltemperatursensor: defekt", "0714");
        put(Group.OIL, "Öltemperatursensor: Messwert unplausibel", "233D");

        put(Group.SLIP, "Gang 1 unplausibel oder Getriebe rutscht", "0731");
        put(Group.SLIP, "Gang 2 unplausibel oder Getriebe rutscht", "0732");
        put(Group.SLIP, "Gang 3 unplausibel oder Getriebe rutscht", "0733");
        put(Group.SLIP, "Gang 4 unplausibel oder Getriebe rutscht", "0734");
        put(Group.SLIP, "Gang 5 unplausibel oder Getriebe rutscht", "0735");
        put(Group.SLIP, "Gang 6 unplausibel oder Getriebe rutscht", "0729");
        put(Group.SLIP, "Gang 7 unplausibel oder Getriebe rutscht", "076F");
        put(Group.SLIP, "Gang unplausibel oder Getriebe rutscht", "0894");
        put(Group.SLIP, "Antriebsstrang: mechanischer Fehler", "0720");
        put(Group.SLIP, "Hydraulik: ein Schaltelement öffnet nicht", "0893");
        put(Group.SLIP, "Hydraulik: ein Schaltelement schließt nicht", "2711");
        put(Group.SLIP, "Wandlerüberbrückungskupplung: lässt sich nicht ansteuern", "0741");
        put(Group.SLIP, "Wandlerüberbrückungskupplung: Schließen nicht zugelassen", "0742");
        put(Group.SLIP, "Wandlerüberbrückungskupplung: Reibleistung zu hoch", "2783");

        put(Group.VALVE, "Magnetventil Arbeitsdruck (Y3/8y1): elektrische Prüfung fehlgeschlagen", "2810");
        put(Group.VALVE, "Magnetventil Kupplung K1 (Y3/8y2): elektrische Prüfung fehlgeschlagen", "0748");
        put(Group.VALVE, "Magnetventil Kupplung K2 (Y3/8y3): elektrische Prüfung fehlgeschlagen", "2734");
        put(Group.VALVE, "Magnetventil Kupplung K3 (Y3/8y4): elektrische Prüfung fehlgeschlagen", "2716");
        put(Group.VALVE, "Magnetventil Bremse B1 (Y3/8y5): elektrische Prüfung fehlgeschlagen", "0778");
        put(Group.VALVE, "Magnetventil Bremse B2 (Y3/8y6): elektrische Prüfung fehlgeschlagen", "2725");
        put(Group.VALVE, "Magnetventil Bremse B3 (Y3/8y7): elektrische Prüfung fehlgeschlagen", "0798");
        put(Group.VALVE, "Magnetventil Wandlerüberbrückung (Y3/8y8): elektrische Prüfung fehlgeschlagen", "2759");

        put(Group.SPEED, "Versorgung der Drehzahlsensoren: Unterspannung", "0642");
        put(Group.SPEED, "Versorgung der Drehzahlsensoren: Überspannung", "0643");
        put(Group.SPEED, "Getriebeeingangswelle: Drehzahl zu hoch", "0716");
        put(Group.SPEED, "Turbinendrehzahlsensor (Y3/8n1): kein Signal", "0717");
        put(Group.SPEED, "Turbinendrehzahlsensor (Y3/8n1): defekt", "0718");
        put(Group.SPEED, "Abtriebsdrehzahl passt nicht zu den Raddrehzahlen", "0721");
        put(Group.SPEED, "Abtriebsdrehzahlsensor (Y3/8n3): kein Signal", "0722");
        put(Group.SPEED, "Abtriebsdrehzahlsensor (Y3/8n3): defekt", "0723");
        put(Group.SPEED, "Interner Drehzahlsensor (Y3/8n2): Drehzahl zu hoch", "2766");
        put(Group.SPEED, "Interner Drehzahlsensor (Y3/8n2): kein Signal", "2767");
        put(Group.SPEED, "Interner Drehzahlsensor (Y3/8n2): defekt", "2768");

        put(Group.SELECTOR, "Wählbereichssensor (Y3/8s1): defekt", "0705");
        put(Group.SELECTOR, "Wählbereichssensor (Y3/8s1): prüfen", "0706");
        put(Group.SELECTOR, "Wählbereichssensor (Y3/8s1): Anlernwerte außerhalb des gültigen Bereichs", "2216");
        put(Group.SELECTOR, "Wählbereichssensor passt nicht zum Wählhebelmodul (N15/5)", "2805");
        put(Group.SELECTOR, "Wählbereichssensor (Y3/8s1): nicht angelernt", "E2806");

        put(Group.UNIT, "Getriebesteuergerät (Y3/8n4): interner Fehler", "0604", "0605", "0607", "060A", "060C", "0613",
                "061B", "062F", "0633", "0641", "0651", "06A3", "0701", "0711", "1610", "1629", "1631", "1632", "1633",
                "1634", "1636", "1638", "1639", "1693", "1710", "1711", "1712");
        put(Group.UNIT, "Getriebesteuergerät (Y3/8n4): defekt – Reparaturhinweise der Werkstatt", "C300");

        put(Group.VOLTAGE, "Versorgung Klemme 87: Unterspannung", "E0562");
        put(Group.VOLTAGE, "Versorgung Klemme 87: Überspannung", "E0563");
        put(EnumSet.of(Group.OIL, Group.VOLTAGE), "Abschaltung wegen Übertemperatur und Überspannung", "E1707");

        put(Group.CAN, "CAN-Controller: Bus abgeschaltet (Bus OFF)", "C001", "C002");
        put(Group.CAN, "Startfreigabe der Fahrberechtigung nicht erteilt", "C426");
        put(Group.CAN, "Startfreigabe-Signal an die Fahrberechtigung fehlt oder ist ungültig", "E0513", "EC513");
        put(Group.CAN, "Zusatzölpumpe: Botschaft fehlt auf dem CAN", "D138");
        put(Group.CAN, "Fahrerwunschmoment: CAN-Botschaft fehlt (Zählerfehler)", "E1240");
        put(Group.CAN, "Fahrerwunschmoment: CAN-Botschaft fehlt (Paritätsfehler)", "E1242");
        put(Group.CAN, "Motorsteuerung lässt keinen Momenteneingriff des Getriebes zu", "E2638");
        put(Group.CAN, "Keine CAN-Botschaft vom Motorsteuergerät", "EC100", "EC55A", "EC55B", "ED10B", "ED400", "ED401",
                "ED402", "ED404", "ED405", "ED406");
        put(Group.CAN, "Keine CAN-Botschaft vom Wählhebelmodul (N15/5)", "EC103", "EC404", "ED420");
        put(Group.CAN, "Keine CAN-Botschaft vom ESP (N47-5)", "EC121", "ED407", "ED408", "ED409", "ED40A", "ED40B",
                "ED40C");
        put(Group.CAN, "Keine CAN-Botschaft vom Mantelrohrmodul (N80)", "EC126", "EC428", "EC429", "ED139", "ED13A");
        put(Group.CAN, "Keine CAN-Botschaft vom Fahrwerkssystem", "EC132");
        put(Group.CAN, "CAN-Signale des Fahrwerkssystems unvollständig", "EC421");
        put(Group.CAN, "Keine CAN-Botschaft vom Zündschloss (EZS, N73)", "EC141");
        put(Group.CAN, "Keine CAN-Botschaft vom Kombiinstrument (A1)", "EC155");
        put(Group.CAN, "Keine CAN-Botschaft von der Klimabedienung (N22)", "EC164");
        put(Group.CAN, "Keine CAN-Botschaft vom Zündschloss (N73) oder Zentralgateway (N93)", "EC167");
        put(Group.CAN, "Signale des Motorsteuergeräts unplausibel", "EC401", "ED507");
        put(Group.CAN, "Signale des ESP (N47-5) unplausibel", "EC415");
        put(Group.CAN, "Signale des Kombiinstruments (A1) unplausibel", "EC423");
        put(Group.CAN, "Signale der Klimabedienung (N22) unplausibel", "EC424");
        put(Group.CAN, "Signale des Zündschlosses (N73) unplausibel", "EC431");
        put(Group.CAN, "Signale des Mantelrohrmoduls (N80) unplausibel", "ED504");
        put(Group.CAN, "Signale des Wählhebelmoduls (N15/5) unplausibel", "ED508");
        put(Group.CAN, "Signale von Zündschloss (N73) oder Zentralgateway (N93) unplausibel", "ED50B");

        put(Group.OTHER, "Variantencodierung oder Konfiguration falsch", "0601");
        put(Group.NONE, "Scheinfehler ohne Bedeutung (laut Tabelle)", "0606");
        put(Group.OTHER, "Getriebesteuergerät nicht codiert", "E0602");
        put(Group.OTHER, "Variantencodierung unplausibel oder fehlt", "E1644");
        put(Group.OTHER, "Temperatursensor Wechselrichter der E-Maschine (nur Hybrid)", "E231B");
        put(Group.OTHER, "Softwarefehler (Funktionsprüfung)", "F000");
    }

    private Vgs7229Codes() {
    }

    /** Eintrag zu den 2 Bytes der Antwort (nur 4-stellige Kennung) oder null. */
    public static Entry lookup(int hi, int lo) {
        return M.get(code(hi, lo));
    }

    /** 5-stellige E-Kennung der Tabelle mit denselben letzten vier Stellen – nur als Möglichkeit, nicht belegt. */
    public static Entry possibleECode(int hi, int lo) {
        return M.get("E" + code(hi, lo));
    }

    static String code(int hi, int lo) {
        return String.format(Locale.US, "%02X%02X", hi & 0xFF, lo & 0xFF);
    }

    /** Hinweise je vorkommender Gruppe, in fester Reihenfolge der Gruppen (Öl zuerst). */
    public static String advice(EnumSet<Group> groups) {
        StringBuilder sb = new StringBuilder();
        for (Group g : groups) {
            sb.append("  Was jetzt – ").append(g.title).append(": ").append(g.advice).append('\n');
        }
        return sb.toString();
    }

    static int size() {
        return M.size();
    }
}
