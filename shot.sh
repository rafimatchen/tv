#!/bin/bash
# Usage: ./shot.sh name  -> shots/name.png (downscaled to 960px wide for review)
set -e
: "${ANDROID_SERIAL:=192.168.1.157:5555}"; export ANDROID_SERIAL
cd "$(dirname "$0")"; mkdir -p shots
adb exec-out screencap -p > "shots/$1.full.png"
sips -Z 960 "shots/$1.full.png" --out "shots/$1.png" >/dev/null
rm -f "shots/$1.full.png"
echo "shots/$1.png"
