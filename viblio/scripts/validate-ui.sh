#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
test_serial="${1:-emulator-5554}"
test_adb="${ANDROID_HOME:?Set ANDROID_HOME}/platform-tools/adb"
[[ "$test_serial" =~ ^emulator-[0-9]+$ ]] || { echo 'Use an Android API 28 emulator, never a personal phone.' >&2; exit 1; }
[[ "$("$test_adb" -s "$test_serial" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || exit 1
[[ "$("$test_adb" -s "$test_serial" shell getprop ro.build.version.sdk | tr -d '\r')" == 28 ]] || { echo 'Synthetic drag fixtures use the Android API 28 event signature.' >&2; exit 1; }
"$test_adb" -s "$test_serial" install --no-streaming -r "${VIBLIO_TEST_APK:-viblio/build/outputs/apk/debug/viblio-debug.apk}"
"$test_adb" -s "$test_serial" install --no-streaming -r viblio/build/outputs/apk/androidTest/debug/viblio-debug-androidTest.apk
if [[ "${VIBLIO_RESET_EMULATOR_DATA:-0}" == 1 ]]; then "$test_adb" -s "$test_serial" shell pm clear br.com.viblio; fi
test_policy="$("$test_adb" -s "$test_serial" shell settings get global hidden_api_policy_p_apps | tr -d '\r')"
test_log="$(mktemp)"
cleanup() {
    if [[ "$test_policy" == null ]]; then
        "$test_adb" -s "$test_serial" shell settings delete global hidden_api_policy_p_apps >/dev/null
    else
        "$test_adb" -s "$test_serial" shell settings put global hidden_api_policy_p_apps "$test_policy" >/dev/null
    fi
    rm -f "$test_log"
}
trap cleanup EXIT
# Only the test harness needs to construct a synthetic framework DragEvent.
# Product drag-and-drop uses public SDK APIs and needs no policy change.
"$test_adb" -s "$test_serial" shell settings put global hidden_api_policy_p_apps 1
test_args=()
if [[ -n "${2:-}" ]]; then test_args=(-e apiAddress "$2" -e apiToken "${3:?Supply the isolated API key}"); fi
test_status=0
timeout 240 "$test_adb" -s "$test_serial" shell am instrument -w "${test_args[@]}" br.com.viblio.test/br.com.viblio.CatalogInstrumentation > "$test_log" 2>&1 || test_status=$?
cat "$test_log"
[[ "$test_status" == 0 ]] || exit "$test_status"
rg -q '^PASS:' "$test_log"
! rg -q '^FAIL:|Process crashed|INSTRUMENTATION_FAILED' "$test_log"
