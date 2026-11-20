# ELM327 – UX-Vertrag für Service-Labor

## Geltungsbereich

Nur Branch `feature/w204-service-lab-mode04-roof-coding-20261120`.
Bisheriger `main` ist read-only. Alle UI-Aktionen nutzen `ServiceLabDialogs`
und `ObdSession` als gemeinsame kanonische Eigentümer.

## Mode-04-Zustandsfolge

1. **Gesperrt:** kein aktiver Adapter, keine flüchtige Freigabe oder kein aktueller
   Mode-03/07/0A-Bericht. Es werden keine Schreibbefehle gesendet.
2. **Einmalfreigabe:** bewusstes Auswählen „für diese Verbindung aktivieren“,
   nie aus Settings/Autostart geladen. Bei Trennen/Neuverbinden zurückgesetzt.
3. **Warnung:** erklärt Datenverlust, Readiness, Dauerhaftigkeit, Stillstand,
   Motor-aus und fehlende automatische Wiederholung. „Abbrechen“ erhält Zustand.
4. **Endgültige Bestätigung:** freier Button erst nach exakter Texteingabe
   `LÖSCHEN`. „Nicht löschen“ bricht folgenlos ab.
5. **Exklusiver Auftrag:** 010C/010D/0142 frisch erneut lesen;
   Preflight prüft Motor aus, Stillstand, ECU-Spannung, Protokoll/Standardheader.
6. **Ausführung:** genau ein Mode 04, kein Retry, auch nicht bei Socketverlust;
   unklarer Status wird **nicht** als Lösch-Erfolg interpretiert.
7. **Abschluss:** 44-Quittung -> ECU hat bestätigt, aber kein Reparaturversprechen;
   sonst „Status unklar“ + manuellen Nachleseweg nennen. Neue Freigabe erforderlich.

## Simulierte Dachbetätigung

`RoofLab` verändert ausschließlich lokale Java-Zustände.
Bedienung: Öffnen/Schließen/Kippen/Stopp, Blockade simulieren. Jeder Bildschirm
kennzeichnet „NUR SIMULATION“; keine Eingabe sendet CAN-/ELM327-Befehle.
Realbetrieb erst nach eigener variantenspezifischer Sicherheits-ADR.

## Codierung/EEPROM-Forschung

Infodialoge enthalten keine sendefähigen Aufträge. Kein EEPROM-Write,
keine Security-Access-Implementierung, keine Umgehung. Steuergerät-Identifikation
und Rücksicherung vor jeder späteren separaten Entscheidung.

## Bedienbarkeit und Ausfall

- Bestehende native Bedienelemente, mindestens 48 dp in Hauptaktionsflächen.
- Statusmeldungen als Klartext, kein Erfolg bei Timeout/fehlendem ACK.
- Kein „Löschen“ im normalen Terminal. Keine versteckte Langdruck-Funktion.
- Keine automatische Wiederholung durch Reconnect, Orientierungswechsel,
  Doppeltipp, Aktivitätsneustart oder Hintergrunddienst.
- Ausführbarkeit nach positivem Dialog wird **im I/O-Auftrag erneut geprüft**.
