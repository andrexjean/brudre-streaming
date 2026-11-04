#!/usr/bin/env bash
# Executado dentro do emulador pelo GitHub Actions.
set -u
adb logcat -c || true
./gradlew connectedDebugAndroidTest --no-daemon --stacktrace > instrumented.log 2>&1
code=$?
adb logcat -d -v time -s BruDreTest:V BruDreTorrent:V BruDrePlayer:V BruDre:V AndroidRuntime:E TestRunner:V ExoPlayerImplInternal:E > logcat.txt || true
adb shell dumpsys meminfo com.brudre.streaming > meminfo.txt 2>&1 || true
echo "$code" > instrumented.exit
tail -n 80 instrumented.log
exit 0
