#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Architecture gate (plan D3): the SIP stack may only be touched inside the `LinphoneEngine` package (iOS) and the
# `linphoneengine` module (Android).
#
#   1. `import linphonesw` (and friends) only under ios/Packages/LinphoneEngine/.
#   2. `import LinphoneEngine` only in the app's composition root (ios/FSVoip/) and inside the package itself.
#   3. Only LinphoneEngine/Package.swift may mention the linphone SDK; no other package may depend on LinphoneEngine
#      (comments are ignored).
#   4. Android: `import org.linphone.*` only under android/linphoneengine/; the `linphoneengine` module is used only by
#      the app module (composition root), and only linphoneengine/build.gradle.kts may depend on linphone-sdk.
#
# Usage:  scripts/check-imports.sh [root]        check the repository (default) or another tree
#         scripts/check-imports.sh --self-test   prove the check fails on a violation

set -euo pipefail

check() {
    local root="$1"
    local status=0
    local packages="$root/ios/Packages"

    [ -d "$packages" ] || { echo "check-imports: $packages not found" >&2; return 2; }

    # 1. linphone imports outside LinphoneEngine
    local hits
    hits=$(grep -rEn --include='*.swift' --include='*.m' --include='*.h' \
        '(^|[[:space:]])(@_exported[[:space:]]+)?import[[:space:]]+(linphonesw|linphone|mediastreamer2|bctoolbox|belle_sip|ortp)\b|canImport\((linphonesw|linphone)\)' \
        "$root/ios" 2>/dev/null | grep -v '/.build/' | grep -v '/DerivedData/' | grep -v "$packages/LinphoneEngine/" || true)
    if [ -n "$hits" ]; then
        echo "FAIL: the SIP stack is imported outside ios/Packages/LinphoneEngine:" >&2
        echo "$hits" >&2
        status=1
    fi

    # 2. `import LinphoneEngine` outside the composition root
    hits=$(grep -rEn --include='*.swift' '(^|[[:space:]])import[[:space:]]+LinphoneEngine\b' "$root/ios" 2>/dev/null \
        | grep -v '/.build/' | grep -v "$packages/LinphoneEngine/" | grep -v "$root/ios/FSVoip/" || true)
    if [ -n "$hits" ]; then
        echo "FAIL: LinphoneEngine is imported outside the app composition root (ios/FSVoip):" >&2
        echo "$hits" >&2
        status=1
    fi

    # 3. package manifests
    local manifest name code
    for manifest in "$packages"/*/Package.swift; do
        name=$(basename "$(dirname "$manifest")")
        [ "$name" = "LinphoneEngine" ] && continue
        code=$(sed 's#//.*##' "$manifest")
        if printf '%s\n' "$code" | grep -Eiq 'linphone|baresip'; then
            echo "FAIL: $manifest mentions the SIP stack; only LinphoneEngine may depend on it" >&2
            status=1
        fi
    done

    # 4. Android (only when the tree has an Android app)
    local android="$root/android"
    if [ -d "$android" ]; then
        hits=$(grep -rEn --include='*.kt' --include='*.java' '^[[:space:]]*import[[:space:]]+org\.linphone\.' "$android" 2>/dev/null \
            | grep -v '/build/' | grep -v "$android/linphoneengine/" || true)
        if [ -n "$hits" ]; then
            echo "FAIL: the SIP stack is imported outside android/linphoneengine:" >&2
            echo "$hits" >&2
            status=1
        fi

        local gradle
        for gradle in "$android"/*/build.gradle.kts; do
            name=$(basename "$(dirname "$gradle")")
            code=$(sed 's#//.*##' "$gradle")
            if [ "$name" != "linphoneengine" ] && printf '%s\n' "$code" | grep -Eq 'libs\.linphone|org\.linphone'; then
                echo "FAIL: $gradle depends on linphone-sdk; only linphoneengine may" >&2
                status=1
            fi
            if [ "$name" != "app" ] && [ "$name" != "linphoneengine" ] && printf '%s\n' "$code" | grep -q ':linphoneengine'; then
                echo "FAIL: $gradle depends on :linphoneengine; only the app module (composition root) may" >&2
                status=1
            fi
        done
    fi

    if [ "$status" -eq 0 ]; then
        echo "check-imports: ok (the SIP stack is only used inside LinphoneEngine / linphoneengine)"
    fi

    return "$status"
}

self_test() {
    local tmp
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' RETURN

    mkdir -p "$tmp/ios/Packages/LinphoneEngine/Sources" "$tmp/ios/Packages/UI/Sources" "$tmp/ios/FSVoip"
    echo 'import linphonesw' > "$tmp/ios/Packages/LinphoneEngine/Sources/Engine.swift"
    echo 'import SwiftUI' > "$tmp/ios/Packages/UI/Sources/View.swift"
    echo 'import LinphoneEngine' > "$tmp/ios/FSVoip/AppServices.swift"
    echo '// depends on nothing (LinphoneEngine is not allowed here)' > "$tmp/ios/Packages/UI/Package.swift"

    check "$tmp" >/dev/null 2>&1 || { echo "self-test: a clean tree was rejected" >&2; return 1; }

    echo 'import linphonesw' > "$tmp/ios/Packages/UI/Sources/Bad.swift"
    if check "$tmp" >/dev/null 2>&1; then echo "self-test: 'import linphonesw' in UI was NOT caught" >&2; return 1; fi
    rm "$tmp/ios/Packages/UI/Sources/Bad.swift"

    echo '@_exported import linphonesw' > "$tmp/ios/Packages/UI/Sources/Bad.swift"
    if check "$tmp" >/dev/null 2>&1; then echo "self-test: '@_exported import linphonesw' was NOT caught" >&2; return 1; fi
    rm "$tmp/ios/Packages/UI/Sources/Bad.swift"

    echo 'import LinphoneEngine' > "$tmp/ios/Packages/UI/Sources/Bad.swift"
    if check "$tmp" >/dev/null 2>&1; then echo "self-test: 'import LinphoneEngine' in UI was NOT caught" >&2; return 1; fi
    rm "$tmp/ios/Packages/UI/Sources/Bad.swift"

    echo '.package(path: "../LinphoneEngine")' > "$tmp/ios/Packages/UI/Package.swift"
    if check "$tmp" >/dev/null 2>&1; then echo "self-test: a LinphoneEngine dependency in UI/Package.swift was NOT caught" >&2; return 1; fi

    rm "$tmp/ios/Packages/UI/Package.swift"
    echo '// depends on nothing' > "$tmp/ios/Packages/UI/Package.swift"

    mkdir -p "$tmp/android/linphoneengine/src" "$tmp/android/callcontroller/src" "$tmp/android/app"
    echo 'import org.linphone.core.Core' > "$tmp/android/linphoneengine/src/Engine.kt"
    echo 'dependencies { implementation(libs.linphone) }' > "$tmp/android/linphoneengine/build.gradle.kts"
    echo 'dependencies { implementation(project(":linphoneengine")) }' > "$tmp/android/app/build.gradle.kts"
    echo 'dependencies { api(project(":sipengine")) }' > "$tmp/android/callcontroller/build.gradle.kts"
    check "$tmp" >/dev/null 2>&1 || { echo "self-test: a clean Android tree was rejected" >&2; return 1; }

    echo 'import org.linphone.core.Call' > "$tmp/android/callcontroller/src/Bad.kt"
    if check "$tmp" >/dev/null 2>&1; then echo "self-test: 'import org.linphone' in callcontroller was NOT caught" >&2; return 1; fi
    rm "$tmp/android/callcontroller/src/Bad.kt"

    echo 'dependencies { implementation(libs.linphone) }' > "$tmp/android/callcontroller/build.gradle.kts"
    if check "$tmp" >/dev/null 2>&1; then echo "self-test: a linphone dependency in callcontroller was NOT caught" >&2; return 1; fi

    echo 'dependencies { implementation(project(":linphoneengine")) }' > "$tmp/android/callcontroller/build.gradle.kts"
    if check "$tmp" >/dev/null 2>&1; then echo "self-test: a :linphoneengine dependency in callcontroller was NOT caught" >&2; return 1; fi

    echo "check-imports self-test: ok (clean trees pass, 4 iOS and 3 Android kinds of violation are caught)"
}

case "${1:-}" in
    --self-test) self_test ;;
    "") check "$(cd "$(dirname "$0")/.." && pwd)" ;;
    *) check "$1" ;;
esac
