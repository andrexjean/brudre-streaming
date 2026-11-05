#!/usr/bin/env bash
# Mede o APK RELEASE (R8) no emulador: abertura, navegação pelo controle e quadros lentos (gfxinfo).
set -u
PKG=com.brudre.streaming
mkdir -p perf
./gradlew assembleRelease -PperfAbi --no-daemon -q > perf/build.log 2>&1 || { echo "falha ao compilar release" > perf/resultado.txt; exit 0; }
APK=app/build/outputs/apk/release/app-release.apk
adb uninstall $PKG > /dev/null 2>&1 || true
adb install -r "$APK" > perf/install.log 2>&1
adb logcat -c
key() { adb shell input keyevent "$1"; sleep "${2:-0.6}"; }
T0=$(date +%s%3N)
adb shell am start -W -n $PKG/.ui.MainActivity > perf/start.txt 2>&1
sleep 12   # vinheta (7 s) + primeiras linhas
adb shell dumpsys gfxinfo $PKG reset > /dev/null
adb exec-out screencap -p > perf/release_home.png
# Navegação típica com o controle: desce pelas linhas, anda nos cards, troca de seção
for i in 1 2 3 4 5; do key KEYCODE_DPAD_DOWN; done
for i in 1 2 3 4 5 6 7 8; do key KEYCODE_DPAD_RIGHT 0.4; done
for i in 1 2 3; do key KEYCODE_DPAD_DOWN; done
for i in 1 2 3 4 5 6; do key KEYCODE_DPAD_RIGHT 0.4; done
for i in 1 2 3 4 5 6 7 8 9 10; do key KEYCODE_DPAD_UP 0.4; done
for i in 1 2 3 4 5 6 7 8 9; do key KEYCODE_DPAD_RIGHT 0.8; done   # passa por todas as abas
key KEYCODE_DPAD_CENTER 6
adb exec-out screencap -p > perf/release_secao.png
for i in 1 2 3 4; do key KEYCODE_DPAD_DOWN; done
for i in 1 2 3 4 5 6; do key KEYCODE_DPAD_RIGHT 0.4; done
adb shell dumpsys gfxinfo $PKG > perf/gfxinfo.txt
adb shell dumpsys meminfo $PKG > perf/meminfo_release.txt
adb logcat -d -v time > perf/logcat_release.txt
alive=$(adb shell pidof $PKG | tr -d '\r')
crash=$(grep -c "FATAL EXCEPTION" perf/logcat_release.txt || true)
{
  echo "release_instalado: $(grep -c Success perf/install.log)"
  echo "processo_vivo: ${alive:-nao}"
  echo "crashes: $crash"
  grep -E "TotalTime|WaitTime" perf/start.txt
  grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile" perf/gfxinfo.txt | head -6
  grep -E "TOTAL PSS|TOTAL:" perf/meminfo_release.txt | head -2
} > perf/resultado.txt
cat perf/resultado.txt
