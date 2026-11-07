#!/usr/bin/env bash
# Gradle-Wrapper des Projekts im JDK-17-Container mit dem gemeinsamen SDK ausführen (kein Java auf dem Host nötig).
#   tools/android-sdk/gradle.sh <Projektpfad> <Gradle-Aufgaben …>
#   z. B. tools/android-sdk/gradle.sh "$PWD" :obdcore:testDebugUnitTest :dashboard:assembleDebug
# SDK-Pfad über ANDROID_SDK_DIR (Standard ~/android-sdk), Gradle-Cache unter ~/.gradle-docker.
set -euo pipefail
proj="$(cd "${1:?Projektpfad angeben}" && pwd)"; shift
sdk="${ANDROID_SDK_DIR:-$HOME/android-sdk}"
mkdir -p "$HOME/.gradle-docker" "$HOME/.android-docker"
# ANDROID_USER_HOME persists the debug keystore: otherwise every build signs with a new key and
# "adb install -r" fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE (app data would be lost).
exec docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp/home -e GRADLE_USER_HOME=/gradle \
  -e ANDROID_HOME=/sdk -e ANDROID_SDK_ROOT=/sdk -e ANDROID_USER_HOME=/android-user \
  -v "$sdk:/sdk:ro" -v "$HOME/.gradle-docker:/gradle" -v "$HOME/.android-docker:/android-user" -v "$proj:/work" -w /work \
  eclipse-temurin:17-jdk ./gradlew --no-daemon "$@"
