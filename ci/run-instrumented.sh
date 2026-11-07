#!/usr/bin/env bash
# Executado dentro do emulador pelo GitHub Actions.
set -u
adb logcat -G 16M || true
adb logcat -c || true
./gradlew connectedDebugAndroidTest --no-daemon --stacktrace > instrumented.log 2>&1
code=$?
adb logcat -d -v time -s BruDreTest:V BruDreTorrent:V BruDrePlayer:V BruDre:V BruDreLive:V BruDreSources:V BruDreSearch:V BruDreTLS:V BruDreWebSeed:V AndroidRuntime:E TestRunner:V ExoPlayerImplInternal:E > logcat.txt || true
adb shell dumpsys meminfo com.brudre.streaming > meminfo.txt 2>&1 || true
mkdir -p shots && adb pull /sdcard/brudre_shots/. shots/ > /dev/null 2>&1 || true
echo "$code" > instrumented.exit
./ci/perf-release.sh || true
tail -n 80 instrumented.log
exit 0
