#!/bin/bash
# Disable packages for user 0, one pm call per adb shell, and log them so restore.sh can undo it.
# Usage: scripts/disable.sh pkg [pkg ...]
: "${ANDROID_SERIAL:=192.168.1.157:5555}"; export ANDROID_SERIAL
LOG="$(dirname "$0")/../disabled_by_us.txt"
for p in "$@"; do
  out=$(adb shell pm disable-user --user 0 "$p" 2>&1)
  echo "$p -> $out"
  if echo "$out" | grep -q "new state: disabled-user"; then
    grep -qx "$p" "$LOG" 2>/dev/null || echo "$p" >> "$LOG"
  fi
done
