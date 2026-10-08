#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Local helper: build the debug APK and run every unit test, with Android Studio's JDK when JAVA_HOME is not set.
set -euo pipefail
cd "$(dirname "$0")"
if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
./gradlew assembleDebug testDebugUnitTest "$@"
