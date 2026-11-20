package de.roman.mercedesobdmonitor;

import java.util.HashMap;
import java.util.Map;

/**
 * Bedeutung der P-Codes am Motorsteuergerät ME9.7 (M272, C350 S204) mit Seite, Bauteil und Teilfehler – eigene
 * deutsche Kurzfassung der ME9.7-Fehlertabelle im Werkstatthandbuch (Mercedes-Codes je P-Code zusammengefasst,
 * „·“ trennt mögliche Teilfehler). Rechts = Bank 1 (Zyl. 1–3), links = Bank 2 (Zyl. 4–6). Android-frei.
 */
public final class Me97Codes {
    private static final Map<String, String> M = new HashMap<>();

    static {
        M.put("P0010", "Nockenwellenmagnet Einlass rechts (Y49/5): Leitungsunterbrechung · Einlassnockenwelle rechts: Stellung falsch");
        M.put("P0011", "Einlassnockenwelle rechts: Stellung falsch");
        M.put("P0012", "Einlassnockenwelle rechts: Stellung falsch");
        M.put("P0013", "Nockenwellenmagnet Auslass rechts (Y49/7): Leitungsunterbrechung (Tabelle nennt auch links Y49/6; nach OBD-Norm Bank 1 = rechts)");
        M.put("P0014", "Auslassnockenwelle rechts: Stellung falsch");
        M.put("P0015", "Auslassnockenwelle rechts: Stellung falsch");
        M.put("P0016", "Einlassnockenwelle rechts dauerhaft Richtung früh · Einlassnockenwelle rechts dauerhaft Richtung spät");
        M.put("P0017", "Auslassnockenwelle rechts dauerhaft Richtung früh · Auslassnockenwelle rechts dauerhaft Richtung spät");
        M.put("P0018", "Einlassnockenwelle links dauerhaft Richtung früh · Einlassnockenwelle links dauerhaft Richtung spät");
        M.put("P0019", "Auslassnockenwelle links dauerhaft Richtung früh · Auslassnockenwelle links dauerhaft Richtung spät");
        M.put("P0020", "Nockenwellenmagnet Einlass links (Y49/4): Leitungsunterbrechung · Einlassnockenwelle links: Stellung falsch");
        M.put("P0021", "Einlassnockenwelle links: Stellung falsch");
        M.put("P0022", "Einlassnockenwelle links: Stellung falsch");
        M.put("P0024", "Auslassnockenwelle links: Stellung falsch");
        M.put("P0025", "Auslassnockenwelle links: Stellung falsch");
        M.put("P0030", "Heizung Lambdasonde rechts vor Kat (G3/4): Unterbrechung");
        M.put("P0031", "Heizung Lambdasonde rechts vor Kat (G3/4): Kurzschluss nach Masse");
        M.put("P0032", "Heizung Lambdasonde rechts vor Kat (G3/4): Kurzschluss nach Plus");
        M.put("P0036", "Heizung Lambdasonde rechts nach Kat (G3/6): Unterbrechung");
        M.put("P0037", "Heizung Lambdasonde rechts nach Kat (G3/6): Kurzschluss nach Masse");
        M.put("P0038", "Heizung Lambdasonde nach Kat: Heizleistung zu gering (Tabelle nennt G3/5 links; nach OBD-Norm Bank 1 = rechts, G3/6)");
        M.put("P0040", "Lambdasonden vor Kat: Stecker vertauscht");
        M.put("P0041", "Lambdasonden nach Kat: Stecker vertauscht");
        M.put("P0050", "Heizung Lambdasonde links vor Kat (G3/3): Unterbrechung");
        M.put("P0051", "Heizung Lambdasonde links vor Kat (G3/3): Kurzschluss nach Masse");
        M.put("P0052", "Heizung Lambdasonde links vor Kat (G3/3): Kurzschluss nach Plus");
        M.put("P0056", "Heizung Lambdasonde links nach Kat (G3/5): Unterbrechung");
        M.put("P0057", "Heizung Lambdasonde links nach Kat (G3/5): Kurzschluss nach Masse");
        M.put("P0058", "Heizung Lambdasonde links nach Kat (G3/5): Kurzschluss nach Plus");
        M.put("P0068", "Luftmasse passt nicht zur Drosselklappenstellung");
        M.put("P0071", "Ansauglufttemperatur passt nicht zur Außentemperatur");
        M.put("P0072", "Außentemperatursensor (B14): Kurzschluss nach Masse");
        M.put("P0073", "Außentemperatursensor (B14): Kurzschluss nach Plus");
        M.put("P0101", "Luftmasse passt nicht zur Drosselklappenstellung · Luftmassenmesser (B2/5): Luftmasse zu gering · Luftmassenmesser (B2/5): Luftmasse zu hoch");
        M.put("P0102", "Luftmassenmesser (B2/5): Unterbrechung oder Kurzschluss");
        M.put("P0104", "Luftmassenmesser (B2/5): Wackelkontakt (niedrige Frequenz) · Luftmassenmesser (B2/5): Wackelkontakt (hohe Frequenz)");
        M.put("P0106", "Drucksensor Saugrohr (B28): Wert unplausibel");
        M.put("P0107", "Drucksensor Saugrohr (B28): Kurzschluss nach Masse");
        M.put("P0108", "Drucksensor Saugrohr (B28): Kurzschluss nach Plus oder Unterbrechung");
        M.put("P0111", "Ansauglufttemperatursensor (B2/5b1): Wert unplausibel · Ansauglufttemperatursensor (B2/5b1): Wert ändert sich nicht");
        M.put("P0112", "Ansauglufttemperatursensor (B2/5b1): Kurzschluss nach Masse");
        M.put("P0113", "Ansauglufttemperatursensor (B2/5b1): Kurzschluss nach Plus oder Unterbrechung");
        M.put("P0115", "Heizungs-Absperrventil (Y16/2): Kurzschluss nach Plus · Heizungs-Absperrventil (Y16/2): Kurzschluss nach Masse · Heizungs-Absperrventil (Y16/2): Leitungsunterbrechung");
        M.put("P0116", "Kühlmitteltemperatursensor (B11/4): Nebenschluss / Kennlinie · Kühlmitteltemperatursensor (B11/4): Kühlmitteltemperatur zu hoch · Kühlmitteltemperatursensor (B11/4): Kühlmitteltemperatur zu niedrig · Motortemperatur unplausibel");
        M.put("P0117", "Kühlmitteltemperatursensor (B11/4): Kurzschluss nach Plus oder Unterbrechung · Kühlmitteltemperatursensor (B11/4): Fehler");
        M.put("P0118", "Kühlmitteltemperatursensor (B11/4): Kurzschluss nach Masse · Kühlmitteltemperatursensor (B11/4): Kurzschluss nach Plus oder Unterbrechung");
        M.put("P0122", "Drosselklappensteller (M16/6): Istwert-Potentiometer 1: Kurzschluss nach Masse");
        M.put("P0123", "Drosselklappensteller (M16/6): Istwert-Potentiometer 1: Kurzschluss nach Plus oder Unterbrechung");
        M.put("P0128", "Thermostat (Y110) klemmt offen: Kühlmittel wird zu langsam warm");
        M.put("P0130", "Lambdasonde rechts vor Kat (G3/4): Spannung zu hoch · Lambdasonde rechts vor Kat (G3/4): Spannung zu niedrig");
        M.put("P0135", "Heizung Lambdasonde rechts vor Kat (G3/4): Kurzschluss nach Plus / Heizwiderstand zu klein · Heizung Lambdasonde rechts vor Kat (G3/4): Heizleistung zu gering");
        M.put("P0136", "Lambdasonde rechts nach Kat (G3/6): Kurzschluss nach Masse · Lambdasonde rechts nach Kat (G3/6): Kurzschluss zwischen Signal- und Heizleitung");
        M.put("P0138", "Lambdasonde rechts nach Kat (G3/6): Kurzschluss nach Plus / Heizwiderstand zu klein");
        M.put("P0139", "Lambdasonde rechts nach Kat (G3/6): gealtert (reagiert zu träge)");
        M.put("P0140", "Lambdasonde rechts nach Kat (G3/6): Signalleitung unterbrochen");
        M.put("P0141", "Heizung Lambdasonde rechts nach Kat (G3/6): Heizleistung zu gering · Heizung Lambdasonde rechts nach Kat (G3/6): Kurzschluss nach Plus");
        M.put("P0150", "Lambdasonde links vor Kat (G3/3): Spannung zu hoch · Lambdasonde links vor Kat (G3/3): Spannung zu niedrig");
        M.put("P0155", "Heizung Lambdasonde links vor Kat (G3/3): Kurzschluss nach Plus / Heizwiderstand zu klein · Heizung Lambdasonde links vor Kat (G3/3): Heizleistung zu gering");
        M.put("P0156", "Lambdasonde links nach Kat (G3/5): Kurzschluss nach Masse · Lambdasonde links nach Kat (G3/5): Kurzschluss zwischen Signal- und Heizleitung");
        M.put("P0158", "Lambdasonde links nach Kat (G3/5): Kurzschluss nach Plus / Heizwiderstand zu klein");
        M.put("P0159", "Lambdasonde links nach Kat (G3/5): gealtert (reagiert zu träge)");
        M.put("P0160", "Lambdasonde links nach Kat (G3/5): Signalleitung unterbrochen");
        M.put("P0170", "Lambdaregelung rechts: Regelung unplausibel · Lambdaregelung rechts: Fehler");
        M.put("P0171", "Gemischadaption rechts Teillast: Anreicherung über Grenze (lief mager) · Gemischadaption rechts Leerlauf: Anreicherung über Grenze (lief mager) · Lambdaregelung rechts: am Fettanschlag");
        M.put("P0172", "Gemischadaption rechts Teillast: Abmagerung unter Grenze (lief fett) · Gemischadaption rechts Leerlauf: Abmagerung unter Grenze (lief fett) · Lambdaregelung rechts: am Mageranschlag");
        M.put("P0173", "Lambdaregelung links: Regelung unplausibel · Lambdaregelung links: Fehler");
        M.put("P0174", "Gemischadaption links Teillast: Anreicherung über Grenze (lief mager) · Gemischadaption links Leerlauf: Anreicherung über Grenze (lief mager) · Lambdaregelung links: am Fettanschlag");
        M.put("P0175", "Gemischadaption links Teillast: Abmagerung unter Grenze (lief fett) · Gemischadaption links Leerlauf: Abmagerung unter Grenze (lief fett) · Lambdaregelung links: am Mageranschlag");
        M.put("P0201", "Einspritzventil Zyl. 1: Leitungsunterbrechung");
        M.put("P0202", "Einspritzventil Zyl. 2: Leitungsunterbrechung");
        M.put("P0203", "Einspritzventil Zyl. 3: Leitungsunterbrechung");
        M.put("P0204", "Einspritzventil Zyl. 4: Leitungsunterbrechung");
        M.put("P0205", "Einspritzventil Zyl. 5: Leitungsunterbrechung");
        M.put("P0206", "Einspritzventil Zyl. 6: Leitungsunterbrechung");
        M.put("P0222", "Drosselklappensteller (M16/6): Istwert-Potentiometer 2: Kurzschluss nach Masse");
        M.put("P0223", "Drosselklappensteller (M16/6): Istwert-Potentiometer 2: Kurzschluss nach Plus oder Unterbrechung");
        M.put("P0261", "Einspritzventil Zyl. 1: Kurzschluss nach Masse");
        M.put("P0262", "Einspritzventil Zyl. 1: Kurzschluss nach Plus");
        M.put("P0264", "Einspritzventil Zyl. 2: Kurzschluss nach Masse");
        M.put("P0265", "Einspritzventil Zyl. 2: Kurzschluss nach Plus");
        M.put("P0267", "Einspritzventil Zyl. 3: Kurzschluss nach Masse");
        M.put("P0268", "Einspritzventil Zyl. 3: Kurzschluss nach Plus");
        M.put("P0270", "Einspritzventil Zyl. 4: Kurzschluss nach Masse");
        M.put("P0271", "Einspritzventil Zyl. 4: Kurzschluss nach Plus");
        M.put("P0273", "Einspritzventil Zyl. 5: Kurzschluss nach Masse");
        M.put("P0274", "Einspritzventil Zyl. 5: Kurzschluss nach Plus");
        M.put("P0276", "Einspritzventil Zyl. 6: Kurzschluss nach Masse");
        M.put("P0277", "Einspritzventil Zyl. 6: Kurzschluss nach Plus");
        M.put("P0300", "Aussetzer mehrerer Zylinder");
        M.put("P0301", "Aussetzer Zyl. 1: katschädigend · Aussetzer Zyl. 1: abgasrelevant · Aussetzer Zyl. 1: abgasrelevant nach Start");
        M.put("P0302", "Aussetzer Zyl. 2: katschädigend · Aussetzer Zyl. 2: abgasrelevant · Aussetzer Zyl. 2: abgasrelevant nach Start");
        M.put("P0303", "Aussetzer Zyl. 3: katschädigend · Aussetzer Zyl. 3: abgasrelevant · Aussetzer Zyl. 3: abgasrelevant nach Start");
        M.put("P0304", "Aussetzer Zyl. 4: katschädigend · Aussetzer Zyl. 4: abgasrelevant · Aussetzer Zyl. 4: abgasrelevant nach Start");
        M.put("P0305", "Aussetzer Zyl. 5: katschädigend · Aussetzer Zyl. 5: abgasrelevant · Aussetzer Zyl. 5: abgasrelevant nach Start");
        M.put("P0306", "Aussetzer Zyl. 6: katschädigend · Aussetzer Zyl. 6: abgasrelevant · Aussetzer Zyl. 6: abgasrelevant nach Start");
        M.put("P0324", "Klopfregelung gestört");
        M.put("P0325", "Klopfsensor rechts (A16/1): elektrischer Fehler");
        M.put("P0327", "Klopfsensor rechts (A16/1): Kurzschluss nach Masse");
        M.put("P0328", "Klopfsensor rechts (A16/1): Kurzschluss nach Plus");
        M.put("P0330", "Klopfsensor links (A16/2): elektrischer Fehler");
        M.put("P0332", "Klopfsensor links (A16/2): Kurzschluss nach Masse");
        M.put("P0333", "Klopfsensor links (A16/2): Kurzschluss nach Plus");
        M.put("P0335", "Kurbelwellen-Hallgeber (B70): kein Signal – Leitung oder Versorgung");
        M.put("P0336", "Kurbelwellengeber: Zahnzahl falsch oder Leitungsfehler · Kurbelwellengeber: Zahnlücke nicht erkannt oder Leitungsfehler · Kurbelwellengeber: Zahnlücke zeitweise nicht erkannt");
        M.put("P0339", "Kurbelwellen-Hallgeber (B70): Signal zeitweise gestört – Leitung oder Versorgung");
        M.put("P0340", "Nockenwellen-Hallgeber nicht erkannt");
        M.put("P0341", "Nockenwellen-Hallgeber Einlass rechts (B6/5): Signalwechsel-Frequenz unplausibel · Nockenwellen-Hallgeber Einlass rechts (B6/5): Zeitpunkt des Signalwechsels unplausibel");
        M.put("P0342", "Nockenwellen-Hallgeber Einlass rechts (B6/5): Kurzschluss nach Masse");
        M.put("P0343", "Nockenwellen-Hallgeber Einlass rechts (B6/5): Kurzschluss nach Plus oder Unterbrechung");
        M.put("P0346", "Nockenwellen-Hallgeber Einlass links (B6/4): Signalwechsel-Frequenz unplausibel · Nockenwellen-Hallgeber Einlass links (B6/4): Zeitpunkt des Signalwechsels unplausibel");
        M.put("P0347", "Nockenwellen-Hallgeber Einlass links (B6/4): Kurzschluss nach Masse");
        M.put("P0348", "Nockenwellen-Hallgeber Einlass links (B6/4): Kurzschluss nach Plus oder Unterbrechung");
        M.put("P0351", "Zündspule Zyl. 1: Primärstrom zu hoch · Zündspule Zyl. 1: Primärstrom zu niedrig · Zündspule Zyl. 1: Diagnosesignal gestört · Zündspule Zyl. 1: Primärstrom schwankt · Zündspule Zyl. 1: Ansteuerleitung unterbrochen · Zündspule Zyl. 1: Endstufe meldet elektrischen Fehler");
        M.put("P0352", "Zündspule Zyl. 2: Primärstrom zu hoch · Zündspule Zyl. 2: Primärstrom zu niedrig · Zündspule Zyl. 2: Diagnosesignal gestört · Zündspule Zyl. 2: Primärstrom schwankt · Zündspule Zyl. 2: Ansteuerleitung unterbrochen · Zündspule Zyl. 2: Endstufe meldet elektrischen Fehler");
        M.put("P0353", "Zündspule Zyl. 3: Primärstrom zu hoch · Zündspule Zyl. 3: Primärstrom zu niedrig · Zündspule Zyl. 3: Diagnosesignal gestört · Zündspule Zyl. 3: Primärstrom schwankt · Zündspule Zyl. 3: Ansteuerleitung unterbrochen · Zündspule Zyl. 3: Endstufe meldet elektrischen Fehler");
        M.put("P0354", "Zündspule Zyl. 4: Primärstrom zu hoch · Zündspule Zyl. 4: Primärstrom zu niedrig · Zündspule Zyl. 4: Diagnosesignal gestört · Zündspule Zyl. 4: Primärstrom schwankt · Zündspule Zyl. 4: Ansteuerleitung unterbrochen · Zündspule Zyl. 4: Endstufe meldet elektrischen Fehler");
        M.put("P0355", "Zündspule Zyl. 5: Primärstrom zu hoch · Zündspule Zyl. 5: Primärstrom zu niedrig · Zündspule Zyl. 5: Diagnosesignal gestört · Zündspule Zyl. 5: Primärstrom schwankt · Zündspule Zyl. 5: Ansteuerleitung unterbrochen · Zündspule Zyl. 5: Endstufe meldet elektrischen Fehler");
        M.put("P0356", "Zündspule Zyl. 6: Primärstrom zu hoch · Zündspule Zyl. 6: Primärstrom zu niedrig · Zündspule Zyl. 6: Diagnosesignal gestört · Zündspule Zyl. 6: Primärstrom schwankt · Zündspule Zyl. 6: Ansteuerleitung unterbrochen · Zündspule Zyl. 6: Endstufe meldet elektrischen Fehler");
        M.put("P0366", "Nockenwellen-Hallgeber Auslass rechts (B6/7): Signalwechsel-Frequenz unplausibel · Nockenwellen-Hallgeber Auslass rechts (B6/7): Zeitpunkt des Signalwechsels unplausibel");
        M.put("P0367", "Nockenwellen-Hallgeber Auslass rechts (B6/7): Kurzschluss nach Masse");
        M.put("P0368", "Nockenwellen-Hallgeber Auslass rechts (B6/7): Kurzschluss nach Plus oder Unterbrechung");
        M.put("P0391", "Nockenwellen-Hallgeber Auslass links (B6/6): Signalwechsel-Frequenz unplausibel · Nockenwellen-Hallgeber Auslass links (B6/6): Zeitpunkt des Signalwechsels unplausibel");
        M.put("P0392", "Nockenwellen-Hallgeber Auslass links (B6/6): Kurzschluss nach Masse");
        M.put("P0393", "Nockenwellen-Hallgeber Auslass links (B6/6): Kurzschluss nach Plus oder Unterbrechung");
        M.put("P0410", "Sekundärluft rechts: ohne Wirkung · Sekundärluft links: ohne Wirkung");
        M.put("P0412", "Umschaltventil Sekundärluftpumpe (Y32): Unterbrechung");
        M.put("P0413", "Umschaltventil Sekundärluftpumpe (Y32): Kurzschluss nach Plus");
        M.put("P0414", "Umschaltventil Sekundärluftpumpe (Y32): Kurzschluss nach Masse");
        M.put("P0418", "Relais Sekundärluftpumpe: Unterbrechung");
        M.put("P0422", "Katalysator rechts: Wirkung zu gering · Katalysator links: Wirkung zu gering");
        M.put("P0442", "Tankentlüftung: kleines Leck (Schlauch oder Absperrventil Aktivkohlefilter)");
        M.put("P0444", "Regenerierventil (Y58/1): Unterbrechung (dauerhaft zu)");
        M.put("P0446", "Absperrventil Aktivkohlefilter (Y58/4): Leitungsunterbrechung · Absperrventil Aktivkohlefilter (Y58/4): dauerhaft offen oder mechanisch defekt");
        M.put("P0447", "Absperrventil Aktivkohlefilter (Y58/4): Kurzschluss nach Plus");
        M.put("P0448", "Absperrventil Aktivkohlefilter (Y58/4): Kurzschluss nach Masse");
        M.put("P0451", "Tankdrucksensor (B4/3): Kurzschluss nach Plus · Tankdrucksensor (B4/3): Kurzschluss nach Masse · Tankdrucksensor (B4/3): Unterbrechung · Tankdrucksensor (B4/3): Signal unplausibel");
        M.put("P0452", "Tankdrucksensor: Kurzschluss nach Masse");
        M.put("P0453", "Tankdrucksensor: Kurzschluss nach Plus");
        M.put("P0455", "Tankentlüftung: großes Leck (Schlauch ab oder Tankdeckel offen)");
        M.put("P0456", "Tankentlüftung: kleines Leck");
        M.put("P0458", "Regenerierventil (Y58/1): Kurzschluss nach Masse (dauerhaft offen)");
        M.put("P0459", "Regenerierventil (Y58/1): Kurzschluss nach Plus (dauerhaft zu)");
        M.put("P0460", "Tankgeber (B4): Signal fehlerhaft");
        M.put("P0506", "Leerlauf beim Kat-Aufheizen zu niedrig");
        M.put("P0507", "Leerlauf beim Kat-Aufheizen zu hoch");
        M.put("P0562", "Versorgung Motorsteuergerät: Batteriespannung zu niedrig");
        M.put("P0563", "Versorgung Motorsteuergerät: Batteriespannung zu hoch");
        M.put("P0597", "Thermostat (Y110): Leitungsunterbrechung");
        M.put("P0598", "Thermostat (Y110): Kurzschluss nach Masse");
        M.put("P0599", "Thermostat (Y110): Kurzschluss nach Plus");
        M.put("P0604", "Motorsteuergerät: interner Fehler");
        M.put("P0605", "Motorsteuergerät: interner Fehler");
        M.put("P0606", "Motorsteuergerät: interner Fehler");
        M.put("P0607", "Motorsteuergerät: Lambdasonden-Auswertung defekt · Versorgung Motorsteuergerät: Spannung für den A/D-Wandler zu niedrig · Motorsteuergerät: interner Fehler");
        M.put("P0620", "Lichtmaschine (G2): Funktionsstörung");
        M.put("P0638", "Drosselklappensteller (M16/6): mechanischer Fehler · Drosselklappensteller (M16/6): nach Tausch nicht angelernt · Drosselklappensteller (M16/6): klemmt (vereist)");
        M.put("P0651", "Fahrpedalsensor (B37): Spannungsversorgung");
        M.put("P0726", "Drehzahlsignal gestört");
        M.put("P1999", "Leerlauf warm über dem zulässigen Bereich · Leerlauf warm unter dem zulässigen Bereich");
        M.put("P2004", "Tumbleklappen Saugrohr: Sensorleitung Kurzschluss nach Masse / Klappenwellen klemmen");
        M.put("P2005", "Tumbleklappen Saugrohr: Sensorleitung Kurzschluss oder Unterbrechung / Klappe mechanisch defekt");
        M.put("P2006", "Tumbleklappen Saugrohr: Sensorleitung unterbrochen / Klappenwellen klemmen");
        M.put("P2008", "Umschaltventil Saugrohr-Längenumschaltung (Y22/6): Unterbrechung · Umschaltventil Tumbleklappen (Y22/9): Unterbrechung");
        M.put("P2009", "Umschaltventil Saugrohr-Längenumschaltung (Y22/6): Kurzschluss nach Masse · Umschaltventil Tumbleklappen (Y22/9): Kurzschluss nach Masse");
        M.put("P2010", "Umschaltventil Saugrohr-Längenumschaltung (Y22/6): Kurzschluss nach Plus · Umschaltventil Tumbleklappen (Y22/9): Kurzschluss nach Plus");
        M.put("P2072", "Drosselklappe klemmt (vereist)");
        M.put("P2088", "Nockenwellenmagnet Einlass rechts (Y49/5): Kurzschluss nach Masse");
        M.put("P2089", "Nockenwellenmagnet Einlass rechts (Y49/5): Kurzschluss nach Plus");
        M.put("P2090", "Nockenwellenmagnet Auslass rechts (Y49/7): Kurzschluss nach Masse · Nockenwellenmagnet Auslass links (Y49/6): Kurzschluss nach Masse");
        M.put("P2091", "Nockenwellenmagnet Auslass rechts (Y49/7): Kurzschluss nach Plus · Nockenwellenmagnet Auslass links (Y49/6): Kurzschluss nach Plus");
        M.put("P2092", "Nockenwellenmagnet Einlass links (Y49/4): Kurzschluss nach Masse");
        M.put("P2093", "Nockenwellenmagnet Einlass links (Y49/4): Kurzschluss nach Plus");
        M.put("P2101", "Drosselklappensteller (M16/6): Endstufe · Drosselklappensteller (M16/6): Stellung · Drosselklappensteller (M16/6): Anlernen fehlerhaft");
        M.put("P2111", "Drosselklappensteller (M16/6): mechanischer Fehler");
        M.put("P2112", "Drosselklappensteller (M16/6): mechanischer Fehler");
        M.put("P2122", "Fahrpedalsensor (B37): Hallgeber 1: Kurzschluss nach Masse oder Unterbrechung");
        M.put("P2123", "Fahrpedalsensor (B37): Hallgeber 1: Kurzschluss nach Plus");
        M.put("P2127", "Fahrpedalsensor (B37): Hallgeber 2: Kurzschluss nach Masse oder Unterbrechung");
        M.put("P2128", "Fahrpedalsensor (B37): Hallgeber 2: Kurzschluss nach Plus");
        M.put("P2135", "Drosselklappensteller (M16/6): Istwert-Potentiometer 1 oder 2 ausgefallen · Drosselklappensteller (M16/6): Istwert-Potentiometer widersprechen sich");
        M.put("P2138", "Fahrpedalsensor (B37): Hallgeber 1 und 2 widersprechen sich · Fahrpedalgeber widersprechen sich");
        M.put("P2176", "Sicherheits-Kraftstoffabschaltung aktiv · Drosselklappensteller (M16/6): Drosselklappe klemmt oder ist schwergängig · Drosselklappensteller (M16/6): Anlernen nötig");
        M.put("P2195", "Lambdasonde rechts (G3/4) vor Kat: Signal verschoben");
        M.put("P2196", "Lambdasonde rechts (G3/4) vor Kat: Signal verschoben");
        M.put("P2197", "Lambdasonde links (G3/3) vor Kat: Signal verschoben");
        M.put("P2198", "Lambdasonde links (G3/3) vor Kat: Signal verschoben");
        M.put("P2227", "Umgebungsdrucksensor im Motorsteuergerät: Fehler");
        M.put("P2228", "Umgebungsdrucksensor im Motorsteuergerät: Fehler");
        M.put("P2229", "Umgebungsdrucksensor im Motorsteuergerät: Fehler");
        M.put("P2237", "Lambdasonde rechts vor Kat (G3/4): Spannung zu hoch · Lambdasonde rechts vor Kat (G3/4): Unterbrechung · Lambdasonde rechts vor Kat (G3/4): Signalspannung unplausibel");
        M.put("P2240", "Lambdasonde links vor Kat (G3/3): Spannung zu hoch · Lambdasonde links vor Kat (G3/3): Unterbrechung · Lambdasonde links vor Kat (G3/3): Signalspannung unplausibel");
        M.put("P2243", "Lambdasonde rechts vor Kat (G3/4): Unterbrechung");
        M.put("P2247", "Lambdasonde links vor Kat (G3/3): Unterbrechung");
        M.put("P2251", "Lambdasonde rechts vor Kat (G3/4): Unterbrechung");
        M.put("P2254", "Lambdasonde links vor Kat (G3/3): Unterbrechung");
        M.put("P2257", "Relais Sekundärluftpumpe: Kurzschluss nach Masse");
        M.put("P2258", "Relais Sekundärluftpumpe: Kurzschluss nach Plus");
        M.put("P2270", "Lambdasonde rechts nach Kat (G3/6): gealtert (reagiert zu träge)");
        M.put("P2271", "Lambdasonde rechts nach Kat (G3/6): gealtert (reagiert zu träge)");
        M.put("P2272", "Lambdasonde links nach Kat (G3/5): gealtert (reagiert zu träge)");
        M.put("P2273", "Lambdasonde links nach Kat (G3/5): gealtert (reagiert zu träge)");
        M.put("P2279", "Falschluft im Ansaugtrakt");
        M.put("P2300", "Zündspule Zyl. 1: Ansteuerleitung Kurzschluss nach Masse");
        M.put("P2301", "Zündspule Zyl. 1: Ansteuerleitung Kurzschluss nach Plus");
        M.put("P2303", "Zündspule Zyl. 2: Ansteuerleitung Kurzschluss nach Masse");
        M.put("P2304", "Zündspule Zyl. 2: Ansteuerleitung Kurzschluss nach Plus");
        M.put("P2306", "Zündspule Zyl. 3: Ansteuerleitung Kurzschluss nach Masse");
        M.put("P2307", "Zündspule Zyl. 3: Ansteuerleitung Kurzschluss nach Plus");
        M.put("P2309", "Zündspule Zyl. 4: Ansteuerleitung Kurzschluss nach Masse");
        M.put("P2310", "Zündspule Zyl. 4: Ansteuerleitung Kurzschluss nach Plus");
        M.put("P2312", "Zündspule Zyl. 5: Ansteuerleitung Kurzschluss nach Masse");
        M.put("P2313", "Zündspule Zyl. 5: Ansteuerleitung Kurzschluss nach Plus");
        M.put("P2315", "Zündspule Zyl. 6: Ansteuerleitung Kurzschluss nach Masse");
        M.put("P2316", "Zündspule Zyl. 6: Ansteuerleitung Kurzschluss nach Plus");
        M.put("P2414", "Lambdasonde rechts vor Kat (G3/4): Signal unplausibel");
        M.put("P2415", "Lambdasonde links vor Kat (G3/3): Signal unplausibel");
        M.put("P2421", "Regenerierventil (Y58/1): dauerhaft offen oder mechanisch defekt");
        M.put("P2422", "Absperrventil Aktivkohlefilter (Y58/4): dauerhaft zu oder mechanisch defekt");
        M.put("P2440", "Sekundärluftventil Bank 1 (rechts): klemmt offen");
        M.put("P2442", "Sekundärluftventil Bank 2 (links): klemmt offen");
        M.put("P2505", "Spannung am Relais Kreis 87 zu hoch · Spannung am Relais Kreis 87 zu niedrig");
        M.put("P2539", "Kraftstoffdrucksensor (B4/7): elektrischer Fehler");
        M.put("P2541", "Tankdrucksensor (B4/3): Kurzschluss nach Masse");
        M.put("P2542", "Tankdrucksensor (B4/3): Kurzschluss nach Plus");
        M.put("P2626", "Lambdasonde rechts vor Kat (G3/4): Unterbrechung");
        M.put("P2629", "Lambdasonde links vor Kat (G3/3): Unterbrechung");
        M.put("P0023", "Nockenwellenmagnet Auslass links (Y49/6): Stromkreis (nicht in der ME9.7-Tabelle, nach OBD-Norm Bank 2)");
    }

    private Me97Codes() { }

    /**
     * Abweichungen von der Tabelle: P0174/P0175 dort mit vertauschtem Regelanschlag (hier wie P0171/P0172: mager →
     * Fettanschlag), P0038/P0013 dort mit der linken Seite trotz OBD-Bank 1 – im Text vermerkt; P0023 fehlt dort.
     */
    static java.util.Set<Map.Entry<String, String>> entries() {
        return M.entrySet();
    }

    /** Mehrere mögliche Teilfehler („·“ oder „/“) – gemeint ist genau einer davon. */
    public static boolean hasAlternatives(String meaning) {
        return meaning.contains(" · ") || meaning.contains(" / ");
    }

    /** Bedeutung am ME9.7 oder null, wenn der Code dort nicht vorkommt. */
    public static String describe(String code) {
        return code == null ? null : M.get(code);
    }

    static int size() {
        return M.size();
    }
}
