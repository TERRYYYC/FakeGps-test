#!/usr/bin/env bash
set -euo pipefail

SERIAL="${1:-ZY22JHW9M4}"
REFERENCE_PACKAGE="com.hopefactory2021.fakegpslocation"
PRODUCT_PACKAGE="name.caiyao.fakegps"
LAB_PACKAGE="name.caiyao.fakegps.mockprovider"
LAB_COMPONENT="$LAB_PACKAGE/name.caiyao.fakegps.mockprovider.MockProviderService"
LAB_APK="${LAB_APK:-app/build/outputs/apk/mockProvider/app-mockProvider.apk}"
OBSERVE_SECONDS="${OBSERVE_SECONDS:-8}"
ADB=(adb -s "$SERIAL")

restore() {
    local restore_status=0
    set +e
    "${ADB[@]}" shell run-as "$LAB_PACKAGE" am startservice \
        -n "$LAB_COMPONENT" \
        -a name.caiyao.fakegps.mockprovider.action.STOP >/dev/null 2>&1
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

"${ADB[@]}" shell run-as "$LAB_PACKAGE" am start-foreground-service \
    -n "$LAB_COMPONENT" \
    -a name.caiyao.fakegps.mockprovider.action.START \
    --es latitude 40.7128 \
    --es longitude -74.0060 \
    --ef accuracy_meters 3.0

sleep 3
lab_pid=$("${ADB[@]}" shell pidof -s "$LAB_PACKAGE" | tr -d '\r')
test -n "$lab_pid"

echo "ACTIVE package=$LAB_PACKAGE pid=$lab_pid"
"${ADB[@]}" shell cmd appops query-op android:mock_location allow | sed 's/^/MOCK_APP /'
"${ADB[@]}" shell dumpsys activity services "$LAB_PACKAGE" \
    | rg -i 'ServiceRecord|MockProviderService|foreground' \
    | head -30
"${ADB[@]}" shell dumpsys location \
    | rg -i -C 2 'mock|gps provider|name\.caiyao\.fakegps\.mockprovider' \
    | head -100
"${ADB[@]}" logcat --pid="$lab_pid" -d -v threadtime \
    | rg 'MockProviderLab' \
    | tail -30

"${ADB[@]}" shell monkey -p com.google.android.apps.maps 1 >/dev/null
sleep "$OBSERVE_SECONDS"
echo "MAPS_FOREGROUND"
"${ADB[@]}" shell dumpsys activity activities \
    | rg -m 2 'mResumedActivity|topResumedActivity'

echo "ACCEPTANCE_ACTIVE_PHASE_COMPLETE"
