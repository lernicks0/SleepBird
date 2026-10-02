#!/usr/bin/env bash
# Capture only the disposable CI emulator, never a connected personal phone.
set -euo pipefail
if [[ "$(adb -e shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo "Screenshots require the disposable Android emulator."
  exit 1
fi
mkdir -p .ci-output/android/screenshots
adb -e install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -e shell pm grant io.github.lernicks0.sleepbird.debug android.permission.POST_NOTIFICATIONS
adb -e shell cmd appops set io.github.lernicks0.sleepbird.debug SCHEDULE_EXACT_ALARM allow
adb -e shell am start -W -n io.github.lernicks0.sleepbird.debug/io.github.lernicks0.sleepbird.MainActivity
sleep 2
adb -e exec-out screencap -p > .ci-output/android/screenshots/phone-light.png
adb -e shell cmd uimode night yes
sleep 2
adb -e exec-out screencap -p > .ci-output/android/screenshots/phone-dark.png
adb -e shell wm size 1920x1200
adb -e shell wm density 160
sleep 2
adb -e exec-out screencap -p > .ci-output/android/screenshots/tablet-dark.png
