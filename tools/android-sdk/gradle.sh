#!/usr/bin/env bash
# Gradle-Wrapper des Projekts im JDK-17-Container mit dem gemeinsamen SDK ausführen (kein Java auf dem Host nötig).
#   tools/android-sdk/gradle.sh <Projektpfad> <Gradle-Aufgaben …>
#   z. B. tools/android-sdk/gradle.sh "$PWD" :obdcore:testDebugUnitTest :dashboard:assembleDebug
# SDK-Pfad über ANDROID_SDK_DIR (Standard ~/android-sdk), Gradle-Cache unter ~/.gradle-docker.
set -euo pipefail
proj="$(cd "${1:?Projektpfad angeben}" && pwd)"; shift
sdk="${ANDROID_SDK_DIR:-$HOME/android-sdk}"
mkdir -p "$HOME/.gradle-docker" "$HOME/.android-docker"
# Fester Signaturschlüssel (siehe build.gradle), falls vorhanden: Verzeichnis mit elm327.keystore und signing.env
# (ELM327_KEYSTORE_PASSWORD=…, ELM327_KEY_PASSWORD=…); Passwörter per --env-file, nicht in der Prozessliste.
# signing.env im Format von docker --env-file: KEY=wert je Zeile, ohne Anführungszeichen und ohne „export“.
signing_dir="${ELM327_SIGNING_DIR:-$HOME/.config/elm327/signing}"
signing=()
if [ -f "$signing_dir/elm327.keystore" ] && [ -f "$signing_dir/signing.env" ]; then
  if grep -qE '^[[:space:]]*export[[:space:]]|=["'"'"']' "$signing_dir/signing.env"; then
    echo "gradle.sh: $signing_dir/signing.env ohne Anführungszeichen und ohne „export“ schreiben (docker --env-file)" >&2
    exit 2
  fi
  signing=(-v "$signing_dir:/signing:ro" --env-file "$signing_dir/signing.env" -e ELM327_KEYSTORE=/signing/elm327.keystore)
elif [ -e "$signing_dir/elm327.keystore" ] || [ -e "$signing_dir/signing.env" ]; then
  echo "gradle.sh: in $signing_dir fehlt elm327.keystore oder signing.env – baue mit dem Debug-Schlüssel," \
       "die APKs lassen sich dann nicht über die fest signierten installieren" >&2
fi
# ANDROID_USER_HOME persists the fallback debug keystore (used only without the stable key from signing_dir):
# otherwise every such build signs with a new key and "adb install -r" fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
exec docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp/home -e GRADLE_USER_HOME=/gradle \
  -e ANDROID_HOME=/sdk -e ANDROID_SDK_ROOT=/sdk -e ANDROID_USER_HOME=/android-user \
  "${signing[@]}" -v "$sdk:/sdk:ro" -v "$HOME/.gradle-docker:/gradle" -v "$HOME/.android-docker:/android-user" -v "$proj:/work" -w /work \
  eclipse-temurin:17-jdk ./gradlew --no-daemon "$@"
