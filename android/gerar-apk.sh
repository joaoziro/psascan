#!/bin/sh
# Roda os testes e gera o APK instalável:  sh android/gerar-apk.sh
# Sai em android/app/build/outputs/apk/release/app-release.apk. Assinatura: a chave de release se
# PSASCAN_KEYSTORE e PSASCAN_KEYSTORE_SENHA estiverem definidas (ver CONTRIBUTING.md); senão, a de debug.
set -e
cd "$(dirname "$0")"
[ -n "$JAVA_HOME" ] || export JAVA_HOME="$HOME/.local/jdk-17"
[ -n "$ANDROID_HOME" ] || export ANDROID_HOME="$HOME/Android/Sdk"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
if [ -x ./gradlew ]; then G=./gradlew; else G="$HOME/.local/gradle-8.10.2/bin/gradle"; fi
"$G" --console=plain testDebugUnitTest assembleRelease
ls -la app/build/outputs/apk/release/*.apk
[ -n "$PSASCAN_KEYSTORE" ] && echo "assinado com a chave de release" || echo "assinado com a chave de debug desta máquina (uso próprio)"
