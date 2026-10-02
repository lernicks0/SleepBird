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
launcher_component="$(adb -e shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -n 1)"
launcher_package="${launcher_component%%/*}"
capture_app() {
  # AOSP Quickstep sometimes stalls on a headless CI host. Remove its ANR overlay,
  # without suppressing or hiding any SleepBird error.
  if [[ "$launcher_component" == */* && "$launcher_package" != io.github.lernicks0.sleepbird* ]]; then
    adb -e shell am force-stop "$launcher_package"
  fi
  adb -e shell am start -W -n io.github.lernicks0.sleepbird.debug/io.github.lernicks0.sleepbird.MainActivity
  sleep 3
  adb -e exec-out screencap -p > ".ci-output/android/screenshots/$1.png"
}
capture_app phone-light
adb -e shell cmd uimode night yes
capture_app phone-dark
adb -e shell wm size 1920x1200
adb -e shell wm density 160
capture_app tablet-dark
