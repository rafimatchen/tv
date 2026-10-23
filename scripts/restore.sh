#!/bin/bash
# Re-enable everything this project disabled, restore the stock launcher as default
# home, and reset Private DNS. Packages that were disabled at the factory
# (see backup/*/packages_disabled.txt) are left alone.
: "${ANDROID_SERIAL:=192.168.1.157:5555}"; export ANDROID_SERIAL
cd "$(dirname "$0")/.."
adb connect "$ANDROID_SERIAL" >/dev/null 2>&1
# One pm call per adb shell. adb reads stdin, so give it /dev/null or it
# swallows the rest of the list and only the first package gets re-enabled.
while read -r p; do
  [ -z "$p" ] && continue
  echo "$p -> $(adb shell pm enable --user 0 "$p" < /dev/null 2>&1)"
done < disabled_by_us.txt
# Stock launcher back as default home
adb shell cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity
# Remove the remote-button accessibility helper, keeping any other enabled services
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
NEW=$(echo "$CUR" | tr ':' '\n' | grep -v '^com.rafi.glasshome/' | grep -v '^null$' | paste -sd: -)
if [ -z "$NEW" ]; then
  adb shell settings delete secure enabled_accessibility_services
  adb shell settings put secure accessibility_enabled 0
else
  adb shell settings put secure enabled_accessibility_services "$NEW"
fi
# Now-playing listener off
adb shell cmd notification disallow_listener com.rafi.glasshome/com.rafi.glasshome.NowPlayingListener
# Screensaver back to Google's Ambient mode
adb shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
# Private DNS back to the Android default
adb shell settings put global private_dns_mode opportunistic
adb shell settings delete global private_dns_specifier
# Optional: remove the custom launcher too (pass --uninstall-launcher)
if [ "$1" = "--uninstall-launcher" ]; then adb shell pm uninstall com.rafi.glasshome; fi
adb shell input keyevent KEYCODE_HOME
echo "Done. Packages re-enabled: $(grep -c . disabled_by_us.txt)"
