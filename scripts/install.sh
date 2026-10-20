#!/bin/bash
# Build Glass Home, install it on the TV, and make it the default home screen.
# Usage: scripts/install.sh            (build + install)
#        scripts/install.sh --no-build (install the last build)
set -e
: "${ANDROID_SERIAL:=192.168.1.157:5555}"; export ANDROID_SERIAL
: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17}"; export JAVA_HOME
cd "$(dirname "$0")/.."
ROOT=$(pwd)
APK=launcher/app/build/outputs/apk/debug/app-debug.apk
PKG=com.rafi.glasshome

adb connect "$ANDROID_SERIAL" >/dev/null 2>&1 || true

if [ "$1" != "--no-build" ]; then
  (cd launcher && ./gradlew -q assembleDebug)
fi

# adb install can hang waiting for an on-screen confirmation on some TVs; fall back to pm install.
if ! perl -e 'alarm shift; exec @ARGV' 90 adb install -r "$APK"; then
  echo "adb install failed or hung; pushing and using pm install"
  adb push "$APK" /data/local/tmp/glasshome.apk
  adb shell pm install -r /data/local/tmp/glasshome.apk
  adb shell rm /data/local/tmp/glasshome.apk
fi

# Location permission is only used to read the Wi-Fi network name for the top-bar pill.
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION || true
adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION || true

adb shell cmd package set-home-activity --user 0 $PKG/.HomeActivity

# On Google TV the stock launcher (HOME priority 2) and the setup wizard's
# RecoveryActivity (priority 1) both outrank any sideloaded launcher, so both
# have to be disabled. restore.sh re-enables them.
for p in com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith; do
  if ! adb shell pm list packages -d | grep -qx "package:$p"; then
    "$ROOT/scripts/disable.sh" "$p"
  fi
done

# Accessibility helper for the YouTube / Prime Video remote buttons (key filter only).
SVC=$PKG/.RemoteButtonService
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
REST=$(echo "$CUR" | tr ':' '\n' | grep -v "^$PKG/" | grep -v '^null$' | paste -sd: -)
# Write the list without ours, then with it. Re-adding clears the "crashed" state
# Android leaves behind when the app was disabled or killed while the service was bound.
if [ -z "$REST" ]; then
  adb shell settings delete secure enabled_accessibility_services
else
  adb shell settings put secure enabled_accessibility_services "$REST"
fi
adb shell settings put secure enabled_accessibility_services "${REST:+$REST:}$SVC"
adb shell settings put secure accessibility_enabled 1

adb shell input keyevent KEYCODE_HOME
echo "HOME now resolves to: $(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
