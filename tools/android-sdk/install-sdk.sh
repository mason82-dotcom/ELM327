#!/usr/bin/env bash
# Android-SDK ohne Root und ohne Java auf dem Host einrichten (JDK 17 im Container).
#   tools/android-sdk/install-sdk.sh [SDK-Verzeichnis]      Standard: ~/android-sdk
# Installiert, was Build und Emulator brauchen: Plattform 37.2 (Android 17 QPR2), Build-Tools 37.0.0,
# Platform-Tools, Emulator und das Systemabbild 37.2 (google_apis_ps16k, x86_64). Idempotent.
set -euo pipefail
SDK="${1:-$HOME/android-sdk}"
mkdir -p "$SDK"
docker run --rm -v "$SDK:/sdk" eclipse-temurin:17-jdk bash -euo pipefail -c '
  cd /sdk
  if [ ! -x cmdline-tools/latest/bin/sdkmanager ]; then
    apt-get update -qq >/dev/null && apt-get install -y -qq unzip curl >/dev/null
    curl -sSL -o /tmp/ct.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
    mkdir -p /tmp/ct && unzip -q /tmp/ct.zip -d /tmp/ct
    mkdir -p cmdline-tools && rm -rf cmdline-tools/latest && mv /tmp/ct/cmdline-tools cmdline-tools/latest
  fi
  yes | cmdline-tools/latest/bin/sdkmanager --sdk_root=/sdk --licenses >/dev/null || true
  cmdline-tools/latest/bin/sdkmanager --sdk_root=/sdk \
    "platforms;android-37.2" "build-tools;37.0.0" "platform-tools" "emulator" \
    "system-images;android-37.2;google_apis_ps16k;x86_64" | tail -1
  ls /sdk/platforms /sdk/build-tools
'
