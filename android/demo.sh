#!/usr/bin/env bash
# Open the app in demo mode (debug build, see DemoConfig.kt) on the running emulator and optionally take a screenshot.
#   ./demo.sh <full|onboarding> [screen] [screenshot.png]
# Environment: ANDROID_SERIAL (device), FSVOIP_APP_ID (default nl.fullstackstudio.fsvoip; add your suffix).
set -euo pipefail
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
APP="${FSVOIP_APP_ID:-nl.fullstackstudio.fsvoip}"
MODE="${1:-full}"; SCREEN="${2:-}"; SHOT="${3:-}"
"$ADB" shell am force-stop "$APP"
"$ADB" shell "run-as $APP sh -c 'mkdir -p files; echo $MODE > files/demo-mode; rm -f files/demo-screen; [ -n \"$SCREEN\" ] && echo $SCREEN > files/demo-screen; true'"
"$ADB" shell am start -n "$APP/nl.fullstackstudio.fsvoip.ui.MainActivity" > /dev/null
if [ -n "$SHOT" ]; then sleep "${DEMO_WAIT:-4}"; "$ADB" exec-out screencap -p > "$SHOT"; echo "$SHOT"; fi
