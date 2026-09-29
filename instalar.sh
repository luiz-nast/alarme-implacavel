#!/usr/bin/env bash
# Compila o app e instala no celular conectado por adb (cabo USB ou depuração sem fio).
# Requisitos: JDK 17+ (JAVA_HOME ou java no PATH) e Android SDK (ANDROID_HOME ou ~/Android/Sdk).
set -euo pipefail
cd "$(dirname "$0")"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
if [ -z "${JAVA_HOME:-}" ] && ! command -v java > /dev/null && [ -d "$HOME/Android/jdk-21" ]; then
    export JAVA_HOME="$HOME/Android/jdk-21"
fi
ADB="$ANDROID_HOME/platform-tools/adb"

if [ "$("$ADB" get-state 2> /dev/null)" != "device" ]; then
    echo "Nenhum celular pronto. Ative a Depuração USB, conecte o cabo e toque em \"Permitir\" no celular."
    "$ADB" devices -l
    exit 1
fi

./gradlew --console=plain assembleRelease
"$ADB" install -r app/build/outputs/apk/release/app-release.apk
"$ADB" shell am start -n com.implacavel.alarme/.MainActivity > /dev/null
echo "Pronto! O Alarme Implacável está aberto no celular."
