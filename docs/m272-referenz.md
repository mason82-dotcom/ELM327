# M272 – Kernaussagen aus der Schulungsunterlage

Quelle: Mercedes-Benz USA, Händlerschulung „M272“ (287 HO 03, PowerPoint als PDF, 28.10.2004, 76 Seiten). Das
Dokument liegt nicht im Repo (Herstellermaterial). Es beschreibt den M272 allgemein (Stand 2004, US-Fahrzeuge mit
Zentral-Gateway N93), **nicht den W204/S204 MJ 2008**. Was hier steht, gilt für den C350 nur, soweit sich der
Motor nicht geändert hat.

| Aussage | Seite | Bedeutung für die App |
|---|---|---|
| ME 9.7: zylinderselektive Einspritzung, Einzelzündspulen mit Diagnose, E-Gas, **LIN-Kommunikation mit der Lichtmaschine**, Drall-/Saugrohrumschaltung, Nachlauf | 40 | Starttest: Ladespannung wird vom ME vorgegeben, ein Wert knapp über 13 V kann gewollt sein (Hinweis im Bericht) |
| Nach dem Löschen von Fehlercodes den **Nachlauf abwarten**, sonst bleiben Fehler stehen; Nachlauf 4 s (80 °C) bis 150 s (−30 °C) | 40–41 | App löscht nichts (Mode 04 und KWP `14` gesperrt); Hinweis für Werkstattbesuche |
| Vor Kat **Breitband-Lambdasonden** (wie M271/OM648), nach Kat **planare Sonden zwischen den beiden Monolithen** | 49–50 | Live-Lambda ≈ 1,00 kommt aus den Breitbandsonden; die Nachkat-Sonde sitzt im Kat, nicht dahinter (Kat-Tendenz: Richtwert) |
| Sondenbezeichnung: G3/3 links vor Kat, G3/5 links im Kat, G3/4 rechts vor Kat, G3/6 rechts im Kat | 49 | Zuordnung links/rechts zu OBD-Bank 1/2 steht dort **nicht**; Bank 1 = Bank mit Zylinder 1 (SAE J1979) |
| **Rücklauffreie Kraftstoffanlage, 3,8 bar**, Druckregler im Kraftstofffilter im Tank | 60, 63–65 | Beide Bänke haben denselben Raildruck: eine einseitige Trim-Abweichung (Bank 1 am C350) kommt nicht vom Kraftstoffdruck (Hinweis im Mode-06-Bankvergleich) |
| Kraftstoffpumpe über Relais N10/2kA im hinteren SAM, vom ME angesteuert, läuft ~1 s nach Zündung an | 62 | Startprobleme: Pumpe beim Zündung-an hörbar? (kein App-Wert) |
| Thermostat kennfeldgesteuert, Kühlerkreis ab 98 °C (oder Außentemperatur > 28 °C) | 57 | passt zum Log: Kühlmittel regelt 99 → ~82 °C unter Last (Warmlauf-Check) |
| Kurbelwellensensor Hall, Geberrad 60−2 | 47 | – |
| Netzwerkbild mit N93 (Zentral-Gateway), EIS N73, VGS Y3/8n4 | 44 | gilt für die damaligen Baureihen; beim W204 ist die Gateway-Topologie offen (ADR-002) |

Nicht in der Unterlage: Diagnosedienste, Kennungen, Byteformate. Für KWP2000-Befehle bleibt es bei den Quellen in
ADR-001, ADR-003 und ADR-004.
