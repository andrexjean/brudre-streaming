#!/usr/bin/env bash
# Executado dentro do emulador pelo GitHub Actions.
set -u
adb logcat -G 16M || true
adb logcat -c || true
# Servidor Torznab de referência (passo anterior do CI): chave por execução e endereço público, se o túnel abriu.
ARGS=()
[ -n "${TORZNAB_KEY:-}" ] && ARGS+=("-Pandroid.testInstrumentationRunnerArguments.torznabKey=$TORZNAB_KEY")
[ -n "${TORZNAB_PUBLIC:-}" ] && ARGS+=("-Pandroid.testInstrumentationRunnerArguments.torznabPublic=$TORZNAB_PUBLIC")
./gradlew connectedDebugAndroidTest --no-daemon --stacktrace "${ARGS[@]}" > instrumented.log 2>&1
code=$?
adb logcat -d -v time -s BruDreTest:V BruDreTorrent:V BruDrePlayer:V BruDre:V BruDreLive:V BruDreSources:V BruDreSearch:V BruDreTLS:V BruDreWebSeed:V BruDreHomolog:V AndroidRuntime:E TestRunner:V ExoPlayerImplInternal:E > logcat.txt || true
adb shell dumpsys meminfo com.brudre.streaming > meminfo.txt 2>&1 || true
grep -o 'SURVEY|.*' logcat.txt > survey.txt || true
mkdir -p shots && adb pull /sdcard/brudre_shots/. shots/ > /dev/null 2>&1 || true
echo "$code" > instrumented.exit
./ci/perf-release.sh || true
tail -n 80 instrumented.log
exit 0
