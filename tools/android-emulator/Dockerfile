# Android-17.2-Emulator für brix-node: Xvfb + x11vnc + noVNC (Browser), ELM327-Laborsimulator.
# Das SDK (Emulator, Systemabbild 37.2) liegt auf dem Host unter ~/android-sdk und wird eingehängt.
FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      xvfb x11vnc novnc websockify fluxbox python3 ca-certificates openjdk-17-jre-headless iproute2 \
      libpulse0 libnss3 libxcomposite1 libxcursor1 libxi6 libxtst6 libgl1 libxkbfile1 libbsd0 libxdamage1 \
      libxrandr2 libasound2t64 libdrm2 libgbm1 libegl1 libxkbcommon-x11-0 libxcb-cursor0 x11-apps imagemagick \
 && rm -rf /var/lib/apt/lists/*
# scrcpy v5.0.1 (08.10.2026): Bildschirm eines echten Handys per WLAN-ADB spiegeln; erscheint im selben noVNC-Fenster.
# Prüfsumme wie von GitHub für scrcpy-linux-x86_64-v5.0.1.tar.gz veröffentlicht.
ADD --checksum=sha256:9f969d30cc574816077edecda65719c1c70b0aee1df1ba5fa00779ac07b8fd6e \
    https://github.com/Genymobile/scrcpy/releases/download/v5.0.1/scrcpy-linux-x86_64-v5.0.1.tar.gz /tmp/scrcpy.tar.gz
RUN mkdir -p /opt/scrcpy && tar -xzf /tmp/scrcpy.tar.gz -C /opt/scrcpy --strip-components=1 && rm /tmp/scrcpy.tar.gz \
 && ln -s /opt/scrcpy/scrcpy /usr/local/bin/scrcpy
# Ein gemeinsamer ADB-Server für Emulator und Handy (das SDK-adb, nicht das mitgelieferte).
ENV ADB=/sdk/platform-tools/adb SCRCPY_SERVER_PATH=/opt/scrcpy/scrcpy-server
COPY entrypoint.sh elm327_lab_simulator.py /opt/emu/
RUN chmod +x /opt/emu/entrypoint.sh
EXPOSE 6080
ENTRYPOINT ["/opt/emu/entrypoint.sh"]
