#!/usr/bin/env bash
set -euo pipefail

SERIAL="${1:-ZY22JHW9M4}"
REFERENCE_PACKAGE="com.hopefactory2021.fakegpslocation"
PRODUCT_PACKAGE="name.caiyao.fakegps"
BENCH_PACKAGE="name.caiyao.fakegps.bench"
BENCH_ACTIVITY="$BENCH_PACKAGE/name.caiyao.fakegps.ui.ComposeActivity"
ACCEPTANCE_ACTIVITY="$BENCH_PACKAGE/name.caiyao.fakegps.mockprovider.MockProviderAcceptanceActivity"
BENCH_APK="${BENCH_APK:-app/build/outputs/apk/debug/app-debug.apk}"
KYIV_LATITUDE="50.4501"
KYIV_LONGITUDE="30.5234"
OBSERVE_SECONDS="${OBSERVE_SECONDS:-8}"
SCREENSHOT_PATH="${SCREENSHOT_PATH:-}"
ADB=(adb -s "$SERIAL")

ui_dump() {
    "${ADB[@]}" exec-out uiautomator dump /dev/tty 2>/dev/null \
        | tr -d '\r' \
        | sed 's/></>\n</g'
}

tap_node() {
    local selector="$1"
    local attempt dump line coordinates x1 y1 x2 y2
    for attempt in $(seq 1 10); do
        dump=$(ui_dump || true)
        line=$(printf '%s\n' "$dump" | awk -v selector="$selector" '
            index($0, selector) && first == "" { first = $0 }
            END { print first }
        ')
        coordinates=$(printf '%s\n' "$line" \
            | sed -nE 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p')
        if [[ -n "$coordinates" ]]; then
            read -r x1 y1 x2 y2 <<<"$coordinates"
            "${ADB[@]}" shell input tap "$(((x1 + x2) / 2))" "$(((y1 + y2) / 2))"
            echo "UI_TAP selector=$selector"
            return 0
        fi
        sleep 1
    done
    echo "Unable to find visible UI node: $selector" >&2
    return 1
}

open_settings() {
    local dump
    "${ADB[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    "${ADB[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
    "${ADB[@]}" shell am start --user 0 -W -n "$BENCH_ACTIVITY" >/dev/null
    dump=$(ui_dump || true)
    if [[ "$dump" != *'text="系统 Mock 位置"'* ]]; then
        tap_node 'content-desc="菜单"'
        tap_node 'text="设置"'
    fi
}

gps_section() {
    "${ADB[@]}" shell dumpsys location \
        | sed -n '/gps provider/,/passive provider/p'
}

assert_provider_is_mock() {
    local full section
    full=$("${ADB[@]}" shell dumpsys location)
    section=$(printf '%s\n' "$full" | sed -n '/gps provider/,/passive provider/p')
    printf '%s\n' "$section" | rg -q 'gps provider \[mock\]'
    printf '%s\n' "$section" | rg -q "$BENCH_PACKAGE"
    printf '%s\n' "$full" | rg -q 'Location\[gps 50\.450100,30\.523400.*mock\]'
    printf '%s\n' "$full" | rg -q 'Location\[fused 50\.450100,30\.523400.*mock\]'
    echo "PROVIDER_MOCK owner=$BENCH_PACKAGE coordinate=$KYIV_LATITUDE,$KYIV_LONGITUDE"
}

assert_provider_is_real() {
    local section
    section=$(gps_section)
    printf '%s\n' "$section" | rg -q 'gps provider:'
    printf '%s\n' "$section" | rg -q 'GnssService'
    if printf '%s\n' "$section" | rg -q 'gps provider \[mock\]|name\.caiyao\.fakegps'; then
        echo "gps provider is still owned by FakeGPS" >&2
        printf '%s\n' "$section" >&2
        return 1
    fi
    echo "PROVIDER_REAL owner=GnssService"
}

acceptance_command() {
    local command="$1"
    "${ADB[@]}" shell am start --user 0 -W -n "$ACCEPTANCE_ACTIVITY" \
        --es command "$command" >/dev/null
}

restore() {
    local restore_status=0
    set +e
    if "${ADB[@]}" shell pm path "$BENCH_PACKAGE" >/dev/null 2>&1; then
        # Cleanup needs the same mock app-op that created the provider. Give it back temporarily,
        # invoke the in-process product Stop path, then prove GNSS truth before restoring reference.
        "${ADB[@]}" shell cmd appops set "$REFERENCE_PACKAGE" android:mock_location deny \
            >/dev/null 2>&1 || restore_status=1
        "${ADB[@]}" shell cmd appops set "$BENCH_PACKAGE" android:mock_location allow \
            >/dev/null 2>&1 || restore_status=1
        acceptance_command stop >/dev/null 2>&1 || restore_status=1
        sleep 2
        assert_provider_is_real >/dev/null 2>&1 || restore_status=1
        "${ADB[@]}" shell am force-stop "$BENCH_PACKAGE" >/dev/null 2>&1
        "${ADB[@]}" shell cmd appops set "$BENCH_PACKAGE" android:mock_location deny \
            >/dev/null 2>&1 || restore_status=1
    fi
    "${ADB[@]}" shell cmd appops set "$REFERENCE_PACKAGE" android:mock_location allow \
        >/dev/null 2>&1 || restore_status=1
    echo "RESTORE bench=deny reference=allow provider=real status=$restore_status"
    return "$restore_status"
}

"${ADB[@]}" get-state >/dev/null
test -f "$BENCH_APK"

current_mock_app=$("${ADB[@]}" shell cmd appops query-op android:mock_location allow | tr -d '\r')
if [[ "$current_mock_app" != "$REFERENCE_PACKAGE" ]]; then
    echo "Refusing to mutate device: expected $REFERENCE_PACKAGE as the sole mock app; got:"
    echo "$current_mock_app"
    exit 2
fi

assert_provider_is_real
trap restore EXIT

"${ADB[@]}" install -r "$BENCH_APK"
for package_name in "$REFERENCE_PACKAGE" "$PRODUCT_PACKAGE" "$BENCH_PACKAGE"; do
    "${ADB[@]}" shell pm path "$package_name" | sed "s/^/INSTALLED $package_name /"
done

"${ADB[@]}" shell pm grant "$BENCH_PACKAGE" android.permission.ACCESS_FINE_LOCATION
"${ADB[@]}" shell pm grant "$BENCH_PACKAGE" android.permission.POST_NOTIFICATIONS \
    >/dev/null 2>&1 || true

# The debug-only DUMP-protected seam resets only .bench test data, then saves Kyiv through the
# real ProfileRepository so ConfigPrefsSync and the service consume the normal effective profile.
acceptance_command prepare_kyiv
sleep 2

"${ADB[@]}" shell cmd appops set "$REFERENCE_PACKAGE" android:mock_location deny
"${ADB[@]}" shell cmd appops set "$BENCH_PACKAGE" android:mock_location allow

open_settings
tap_node 'checkable="true" checked="false"'
sleep 3
assert_provider_is_mock

bench_pid=$("${ADB[@]}" shell pidof -s "$BENCH_PACKAGE" | tr -d '\r')
test -n "$bench_pid"
echo "ACTIVE package=$BENCH_PACKAGE pid=$bench_pid"
"${ADB[@]}" shell dumpsys activity services "$BENCH_PACKAGE" \
    | rg -i 'ServiceRecord|MockProviderService|isForeground' \
    | sed -n '1,40p'
gps_section | sed -n '1,80p'
"${ADB[@]}" logcat --pid="$bench_pid" -d -v threadtime \
    | rg 'MockProviderMain' \
    | tail -30

"${ADB[@]}" shell monkey -p com.google.android.apps.maps 1 >/dev/null
sleep "$OBSERVE_SECONDS"
echo "MAPS_FOREGROUND coordinate=$KYIV_LATITUDE,$KYIV_LONGITUDE"
"${ADB[@]}" shell dumpsys activity activities \
    | rg 'mResumedActivity|topResumedActivity' \
    | sed -n '1,2p'
if [[ -n "$SCREENSHOT_PATH" ]]; then
    "${ADB[@]}" exec-out screencap -p >"$SCREENSHOT_PATH"
    echo "MAPS_SCREENSHOT path=$SCREENSHOT_PATH"
fi
echo "ACCEPTANCE_ACTIVE_PHASE_COMPLETE"

open_settings
tap_node 'checkable="true" checked="true"'
sleep 2
assert_provider_is_real
echo "ACCEPTANCE_STOP_PHASE_COMPLETE"

restore
restored_mock_app=$("${ADB[@]}" shell cmd appops query-op android:mock_location allow | tr -d '\r')
test "$restored_mock_app" = "$REFERENCE_PACKAGE"
assert_provider_is_real

"${ADB[@]}" shell monkey -p "$REFERENCE_PACKAGE" 1 >/dev/null
sleep 2
echo "REFERENCE_APP_FOREGROUND"
"${ADB[@]}" shell dumpsys activity activities \
    | rg 'mResumedActivity|topResumedActivity' \
    | rg "$REFERENCE_PACKAGE" \
    | sed -n '1,2p'
echo "ACCEPTANCE_RESTORE_PHASE_COMPLETE"
trap - EXIT
