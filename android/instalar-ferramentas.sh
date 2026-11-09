#!/bin/sh
# Instala, sem sudo, o que falta para gerar o APK: JDK 17, Android SDK (linha de comando) e Gradle.
# Tudo fica na pasta pessoal: ~/.local/jdk-17, ~/Android/Sdk e ~/.local/gradle-8.10.2.
# Depois:  sh android/gerar-apk.sh
set -e
LOCAL="$HOME/.local"
SDK="$HOME/Android/Sdk"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$LOCAL" "$SDK"

if [ ! -x "$LOCAL/jdk-17/bin/java" ]; then
    echo "== JDK 17 (Adoptium)"
    curl -fL# -o "$TMP/jdk.tar.gz" "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
    mkdir -p "$TMP/jdk" && tar -xzf "$TMP/jdk.tar.gz" -C "$TMP/jdk"
    mv "$TMP/jdk/"* "$LOCAL/jdk-17"
fi
export JAVA_HOME="$LOCAL/jdk-17"

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "== Android command-line tools (Google)"
    curl -fL# -o "$TMP/cmd.zip" "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    unzip -q "$TMP/cmd.zip" -d "$TMP/cmd"
    mkdir -p "$SDK/cmdline-tools" && mv "$TMP/cmd/cmdline-tools" "$SDK/cmdline-tools/latest"
fi

echo "== licença do Android SDK e pacotes (plataforma 35, build-tools 35.0.0, platform-tools)"
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses > /dev/null
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platforms;android-35" "build-tools;35.0.0" "platform-tools"

if [ ! -x "$LOCAL/gradle-8.10.2/bin/gradle" ]; then
    echo "== Gradle 8.10.2"
    curl -fL# -o "$TMP/gradle.zip" "https://services.gradle.org/distributions/gradle-8.10.2-bin.zip"
    unzip -q "$TMP/gradle.zip" -d "$LOCAL"
fi

echo "pronto: JDK em $JAVA_HOME, SDK em $SDK, Gradle em $LOCAL/gradle-8.10.2"
