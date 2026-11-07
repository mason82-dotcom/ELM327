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

Read-only-Grenze der Apps bleibt unberührt: Der Emulator beantwortet nur, was die App fragt.
