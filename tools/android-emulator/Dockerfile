# Laufzeit für den Android-Emulator (SDK wird von außen eingebunden, siehe start.sh).
FROM eclipse-temurin:17-jdk
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    libpulse0 libnss3 libxcomposite1 libxcursor1 libxi6 libxtst6 libgl1 libegl1 libxdamage1 libxrandr2 \
    libasound2t64 libdbus-1-3 libxkbfile1 libbsd0 libgbm1 libdrm2 libx11-xcb1 libxcb-cursor0 libfontconfig1 \
    && rm -rf /var/lib/apt/lists/*
