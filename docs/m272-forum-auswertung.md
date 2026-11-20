# M272: Auswertung „The Complete M272 Engine Thread“ (MBClub UK) und Servicebulletin

Gelesen am 20.11.2026: alle 7 Seiten (Beiträge #1–#129, 10.09.2012 bis 22.05.2025) von
<https://forums.mbclub.co.uk/threads/the-complete-m272-engine-thread.137229/> und das im Thread verlinkte
Mercedes-Bulletin **MBUSA Dealer Technical Bulletin S-B-03.30/08i vom 23.04.2009** (Kopie auf benzbits.com,
10 Seiten). Forenbeiträge sind Einzelberichte; belegt ist nur, was im Bulletin steht.

## Was das Bulletin festlegt

| Szenario | Fehlerbild | Fehlercodes (ME, Mercedes-Nummern) | betroffene M272 | Baureihe 204 |
|---|---|---|---|---|
| 1 | Nockenwellen falsch zur Kurbelwelle: verschlissenes **Ausgleichswellenrad** (M272) bzw. Umlenkrad (M273), selten gelängte Kette | 1200 und/oder 1208, kommen nach dem Löschen **sofort** wieder | bis Motornummer **2729..30 468993** | **ausdrücklich ausgenommen** („Not Applicable to Model 164.156, 204 or 216.386“) |
| 2 | defekter **Nockenwellenversteller-Magnet** | 0059, 0060, 0063, 0064, 0271, 0272, 0275, 0276 | bis **2729..30 759427** | betroffen (204.054/056/081/987 in der Modellliste) |
| 3 | **Öl am Versteller-Magnet** (unter dem Dichtring oder durch den Stecker), kein Fehlercode | – | bis **2729..30 895140** | betroffen |

- Neuer Magnet: A272 051 01 77; Szenario 1 tauscht zusätzlich Ölpumpe, Kettenspanner und alle vier Magnete,
  Ölwechsel zweimal (nach 15 Meilen Volllast erneut).
- Reparatur Szenario 1: Steuergehäusedeckel ab, laut Arbeitswerten mit Motorausbau; Zylinderköpfe bleiben drauf.
- „Flashen des ME behebt das nicht.“
- Die Revision f (06/2008) hat die Baureihe 204 aufgenommen – für die Szenarien 2 und 3.
- Die Fehlercodes sind Mercedes-Nummern aus dem ME, keine OBD-Codes. Laut einem Forenbericht (#38) zeigt ein
  OBD-Leser 1208 als P0016 und 1200 als P0017 (Einlass/Auslass, **rechte** Zylinderbank).

## Was der Thread zusätzlich berichtet (Einzelberichte)

| Thema | Inhalt | Beiträge |
|---|---|---|
| Motornummer prüfen | steht auf der Datenkarte bzw. im Fahrzeugbrief; im Thread mehrfach per FIN abgefragt. Forenzitat „468933“ weicht vom Bulletin (468993) ab | #2, #24–#26, #42 |
| Kosten | Ausgleichswelle bei MB £2 800 (#46, #65), bei Spezialisten £1 600 (#104); Magnet £17,50 je Stück (Rechnung, #43), mit Einbau ca. £90–120 (#9, #35); Kulanz 54–60 % bei lückenlosem Scheckheft (#9, #16) | |
| Saugrohr | Kunststoff-Stellarm der Längenumschaltung bricht, weil die Rampe verkokt; MB liefert nur das ganze Saugrohr, Metallarme aus dem Zubehör; Folge Motorleuchte (zeitweise, später dauernd) | #91, #118, #122, #128 |
| Kurbelgehäuseentlüftung | **Y-Stück des schwarzen Entlüftungsschlauchs unter dem Luftmassenmesser reißt**, häufig bei 100 000–160 000 km; Fehler „Gemisch falsch, eine oder beide Bänke“, in Xentry unruhige Lambdaregelung; Schlauch ca. 26–30 €; beim Ausbau des LMM fällt leicht das Metallgitter heraus. Prüfung: Starthilfespray am Schlauch, Leerlauf steigt | #96–#98 |
| PCV-Ventil | hoher Ölverbrauch, schwer zugänglich (Hitzeschutz, Spritzwand) | #90 |
| Ölverbrauch | Qualm beim Kaltstart = Ventilschaftdichtungen; 0,5 l je 2 000–3 000 Meilen gilt im Thread als normal | #93–#95 |
| Aussetzer | Zylinder 2 und 3 setzen bei langem Leerlauf aus (Ventilschaftdichtungen vermutet) | #113 |
| Steuerkette | Rasseln beim Kaltstart als Zeichen; bei CGI-Motoren keine Ausgleichswellenschäden gesehen | #89, #91 |
| Baujahre | „2008 und später in Ordnung“ (#60, #117) und ein 2008er R171 außerhalb der Risiko-Nummern (#123) – Meinungen; belegt ist nur die Nummernliste | |

Nicht im Thread: Messwerte, Grenzwerte, Nockenwellen-Abweichung in °KW, Live-Daten, Fuel-Trim-Zahlen.

## Bedeutung für den C350 S204 (MJ 2008) und die App

1. **Ausgleichswelle: für die Baureihe 204 laut Bulletin nicht betroffen.** Das ist die wichtigste Entwarnung.
   Ein Fehlercode zur Nockenwellenlage (P0016–P0019) hätte beim W204 andere Ursachen (Kette, Versteller, Sensor).
2. **Versteller-Magnete und Öl am Stecker: möglich**, abhängig von der Motornummer (≤ 759427 bzw. ≤ 895140).
   Die Motornummer steht nicht im OBD; sie steht auf dem Motor und der Datenkarte. Ein Blick auf die vier
   Magnete vorn an den Zylinderköpfen (Öl am Stecker) kostet nichts.
3. **Bank 1 fetter korrigiert (+6–8 % gegenüber Bank 2, seit 07.11. stabil):**
   - Der gerissene Entlüftungsschlauch unter dem LMM zieht Falschluft **hinter** dem Luftmassenmesser, also für
     beide Bänke. Er passt zu einem Anteil beider Bänke (Bank 2 liegt auch bei +4,7 % LTFT), erklärt aber den
     Unterschied zwischen den Bänken nicht allein. Trotzdem zuerst prüfen: billig, häufig, im Thread mehrfach.
   - Für den **Bankunterschied** bleiben einseitige Ursachen: Abgasleck vor der Vorkat-Sonde, Sonde selbst,
     Einspritzventile der Bank. Der Kraftstoffdruck ist für beide Bänke gleich (rücklauffrei, 3,8 bar).
   - Zuordnung (Ableitung, nicht belegt): Der Forenbericht #38 nennt 1208/P0016 „rechte Zylinderbank“, P0016 ist
     OBD-Bank 1. Bank 1 = rechte Bank (Zylinder 1–3), Vorkat-Sonde dort laut M272-Schulungsunterlage **G3/4**.
4. **Aussetzer Zylinder 2/3 im Leerlauf (#113)** liegen ebenfalls auf Bank 1. Die App zeigt Aussetzer je Zylinder
   über Mode 06 („Aussetzer“) – warm nach einer längeren Leerlaufphase lesen.
5. **Saugrohr-Stellarm:** meldet sich als P2004-Familie; die App hat dafür schon einen Hinweis.

## In der App umgesetzt

- `M272Hints`: P0016–P0019 mit dem Löschtest aus dem Bulletin und der Ausnahme für die Baureihe 204;
  P0010–P0024 mit den Motornummer-Grenzen der Magnete; „beide Bänke mager“ mit dem Y-Stück unter dem LMM.
