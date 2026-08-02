#!/usr/bin/env bash
set -euo pipefail

SERIAL="${1:-ZY22JHW9M4}"
REFERENCE_PACKAGE="com.hopefactory2021.fakegpslocation"
PRODUCT_PACKAGE="name.caiyao.fakegps"
LAB_PACKAGE="name.caiyao.fakegps.mockprovider"
LAB_ACTIVITY="$LAB_PACKAGE/.MockProviderActivity"
LAB_APK="${LAB_APK:-app/build/outputs/apk/mockProvider/app-mockProvider.apk}"
OBSERVE_SECONDS="${OBSERVE_SECONDS:-8}"
SCREENSHOT_PATH="${SCREENSHOT_PATH:-}"
ADB=(adb -s "$SERIAL")

foreground_lab() {
    "${ADB[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    "${ADB[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
    "${ADB[@]}" shell am start --user 0 -W -n "$LAB_ACTIVITY" >/dev/null
}

tap_text() {
    local target="$1"
    local attempt line coordinates x1 y1 x2 y2

    for attempt in $(seq 1 10); do
        line=$(
            "${ADB[@]}" exec-out uiautomator dump /dev/tty 2>/dev/null \
                | tr -d '\r' \
                | sed 's/></>\n</g' \
                | rg -m 1 "text=\"$target\"" \
                || true
        )
        coordinates=$(
            printf '%s\n' "$line" \
                | sed -nE 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p'
        )
        if [[ -n "$coordinates" ]]; then
            read -r x1 y1 x2 y2 <<<"$coordinates"
            "${ADB[@]}" shell input tap "$(((x1 + x2) / 2))" "$(((y1 + y2) / 2))"
            echo "UI_TAP text=$target"
            return 0
        fi
        sleep 1
    done

    echo "Unable to find visible UI control: $target" >&2
    return 1
}

restore() {
    local restore_status=0
    set +e
    if "${ADB[@]}" shell pm path "$LAB_PACKAGE" >/dev/null 2>&1; then
        foreground_lab >/dev/null 2>&1
        tap_text "Stop" >/dev/null 2>&1 || restore_status=1
    fi
    sleep 1
    "${ADB[@]}" shell am force-stop "$LAB_PACKAGE" >/dev/null 2>&1
    "${ADB[@]}" shell cmd appops set "$LAB_PACKAGE" android:mock_location deny \
        >/dev/null 2>&1 || restore_status=1
    "${ADB[@]}" shell cmd appops set "$REFERENCE_PACKAGE" android:mock_location allow \
        >/dev/null 2>&1 || restore_status=1
    echo "RESTORE lab=deny reference=allow status=$restore_status"
    return "$restore_status"
}

"${ADB[@]}" get-state >/dev/null
test -f "$LAB_APK"

current_mock_app=$(
    "${ADB[@]}" shell cmd appops query-op android:mock_location allow | tr -d '\r'
)
if [[ "$current_mock_app" != "$REFERENCE_PACKAGE" ]]; then
    echo "Refusing to mutate device: expected $REFERENCE_PACKAGE as the sole mock app; got:"
    echo "$current_mock_app"
    exit 2
fi

trap restore EXIT

"${ADB[@]}" install -r "$LAB_APK"
for package_name in "$REFERENCE_PACKAGE" "$PRODUCT_PACKAGE" "$LAB_PACKAGE"; do
    "${ADB[@]}" shell pm path "$package_name" | sed "s/^/INSTALLED $package_name /"
done

"${ADB[@]}" shell pm grant "$LAB_PACKAGE" android.permission.ACCESS_FINE_LOCATION
"${ADB[@]}" shell pm grant "$LAB_PACKAGE" android.permission.POST_NOTIFICATIONS \
    >/dev/null 2>&1 || true

"${ADB[@]}" shell cmd appops set "$REFERENCE_PACKAGE" android:mock_location deny
"${ADB[@]}" shell cmd appops set "$LAB_PACKAGE" android:mock_location allow

foreground_lab
tap_text "Start"

sleep 3
lab_pid=$("${ADB[@]}" shell pidof -s "$LAB_PACKAGE" | tr -d '\r')
test -n "$lab_pid"

echo "ACTIVE package=$LAB_PACKAGE pid=$lab_pid"
"${ADB[@]}" shell cmd appops query-op android:mock_location allow | sed 's/^/MOCK_APP /'
"${ADB[@]}" shell dumpsys activity services "$LAB_PACKAGE" \
    | rg -i 'ServiceRecord|MockProviderService|foreground' \
    | sed -n '1,30p'
"${ADB[@]}" shell dumpsys location \
    | rg -i -C 2 'mock|gps provider|name\.caiyao\.fakegps\.mockprovider' \
    | sed -n '1,100p'
"${ADB[@]}" logcat --pid="$lab_pid" -d -v threadtime \
    | rg 'MockProviderLab' \
    | tail -30

"${ADB[@]}" shell monkey -p com.google.android.apps.maps 1 >/dev/null
sleep "$OBSERVE_SECONDS"
echo "MAPS_FOREGROUND"
"${ADB[@]}" shell dumpsys activity activities \
    | rg 'mResumedActivity|topResumedActivity' \
    | sed -n '1,2p'
if [[ -n "$SCREENSHOT_PATH" ]]; then
    "${ADB[@]}" exec-out screencap -p >"$SCREENSHOT_PATH"
    echo "MAPS_SCREENSHOT path=$SCREENSHOT_PATH"
fi

echo "ACCEPTANCE_ACTIVE_PHASE_COMPLETE"
