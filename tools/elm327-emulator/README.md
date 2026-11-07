# ELM327-Emulator (ohne Fahrzeug, ohne Adapter)

[Ircama/ELM327-emulator](https://github.com/Ircama/ELM327-emulator) 4.0.0 als kleiner Docker-Dienst. Das Handy
verbindet sich im Heimnetz-WLAN darauf wie auf einen WLAN-ELM327-Adapter – kein Android-Emulator, kein VNC.
Lizenz des Emulators: CC BY-NC-SA 4.0 (private, nicht-kommerzielle Nutzung); er ist nicht Teil der Apps.

## Starten (brix-node)

```bash
cd ~/Projekte/ELM327/tools/elm327-emulator
docker compose up -d --build
```

In beiden Apps als ELM327-Adresse **`192.168.178.50`** und Port **`35000`** eintragen (statt 192.168.0.10 des echten
WLAN-Adapters); das Handy bleibt dabei im Heimnetz-WLAN. Nur an die LAN-Adresse
gebunden; andere Adresse oder Port mit `ELM_BIND` / `ELM_PORT` in einer `.env` neben `compose.yaml`.

## Was er liefert

Szenario `car` (Toyota Auris Hybrid, vom Emulator mitgebracht): ELM327 v1.5, automatische Protokollwahl
(CAN 11 bit/500 kbit/s), Mode 01 mit mehreren PIDs (Drehzahl, Tempo, Kühlmittel, Status …), Fehlercodes 03/07/0A,
Fahrgestellnummer (09 02). Das ist **nicht** der C350 (M272): Werte und unterstützte PIDs weichen ab.

Eigenes Fahrzeug später: mit `obd_dictionary` (im selben Paket) einmal über einen echten ELM327 am C350 alle
Standard-Anfragen mitschneiden und das Wörterbuch als eigenes Szenario einbinden (Anleitung im Emulator-README,
Abschnitt „obd_dictionary“).

## Prüfen und Mitlesen

```bash
docker logs -f elm327-emulator          # jede Anfrage der App erscheint im Protokoll
docker inspect -f '{{.State.Health.Status}}' elm327-emulator
```

## Prüfung „kein alter Wert“ (vor jeder Freigabe, etwa 3 Minuten)

Sicherheitsregel beider Apps: Ein Wert, der nicht frisch gelesen wurde, zeigt „–“ – nie den letzten Wert.

1. App mit `192.168.178.50:35000` verbinden, LIVE zeigt Drehzahl, Tempo, Kühlmittel.
2. `docker stop elm327-emulator` – innerhalb weniger Sekunden zeigen **alle** Werte „–“, Zeiger ohne Wert,
   Verlauf mit Lücke; Fuel-Trim-/Warmlauf-Test brechen ab statt mit alten Werten zu rechnen.
3. `docker start elm327-emulator` – die App verbindet neu, Werte erscheinen wieder; der Verlauf verbindet die
   Zeit des Ausfalls **nicht** mit einer Linie.
4. Handy drehen und App kurz in den Hintergrund legen: keine alten Werte, keine geschlossenen Lücken.

Automatisch getestet sind die Bausteine: Einstufung jeder Antwort (`PollOutcomeTest`) und das Alterslimit von
15 s (`LiveValuesTest`). Die Freshness-Sperre im Poll-Loop selbst (Zeitüberschreitung einzelner PIDs, Sitzungswechsel)
hängt an Android und ist **nicht** automatisch getestet; der Emulator kann einzelne Zeitüberschreitungen nicht
gezielt erzeugen. Schritt 2 oben prüft den Fall „keine Antwort mehr“ insgesamt.

## Fehlerszenario (Port 35001)

Zweiter Dienst `elm327-faults` mit demselben Emulator, der in einem festen Zyklus (etwa 95 s) Fehler einspielt.
In der App als Port **35001** statt 35000 eintragen; die aktuelle Phase steht im Log:

```bash
docker logs -f elm327-faults | grep PHASE
```

| Phase | Dauer | Emulator | Erwartung in der App |
|---|---|---|---|
| NORMAL | 20 s | alle Werte | alle Werte sichtbar |
| RPM_NO_DATA | 15 s | 01 0C → NO DATA | nur Drehzahl „–“, Rest bleibt |
| RPM_TIMEOUT | 15 s | 01 0C antwortet erst nach 3 s | Drehzahl sofort „–“, Rest bleibt; die verspätete Antwort darf **nicht** als anderer Wert erscheinen |
| RPM_CAN_ERROR | 10 s | 01 0C → CAN ERROR | Drehzahl „–“ |
| ENGINE_OFF | 15 s | Ircama-Szenario `engineoff` (Zündung an, Motor aus) | alle Motorwerte „–“ |
| ADAPTER_SILENT | 20 s | Emulator antwortet nicht | alle Werte „–“, Verlauf mit Lücke, App verbindet neu |

Dauern lassen sich mit `FAULT_NORMAL`, `FAULT_NO_DATA`, `FAULT_TIMEOUT`, `FAULT_CAN_ERROR`, `FAULT_ENGINE_OFF`,
`FAULT_SILENT` (Sekunden) im `environment` des Dienstes ändern. Gesteuert wird über die Standardeingabe des
Batch-Modus (`faults.sh`), ohne Änderung am Emulator.

Automatische Gegenprobe, ob das Szenario selbst stimmt (fragt wie die App ab, Python-Standardbibliothek):

```bash
python3 tools/elm327-emulator/check_faults.py            # 192.168.178.50 35001, ein Zyklus (110 s)
```

Erwartet: sechsmal `OK`, Exit-Code 0. Die Zeile `OTHER` sind verspätete Antworten, die nach einer
Zeitüberschreitung als Antwort auf die nächste Frage eintreffen – genau so verhält sich ein echter Adapter.

## Bekannte Schwäche des Emulators

ELM327-emulator 4.0.0 beendet sich, wenn ein Client die Verbindung hart trennt, während der Emulator ein Zeichen
als Echo zurückschickt (`BrokenPipeError`/`ConnectionResetError` in `read_from_device`). Der Container startet ihn
deshalb in einer Schleife sofort neu: nach etwa 2 s antwortet er wieder, ohne Neustart des Containers (getestet mit
fünf harten Abbrüchen). Die App muss sich danach neu verbinden.

Read-only-Grenze der Apps bleibt unberührt: Der Emulator beantwortet nur, was die App fragt.
