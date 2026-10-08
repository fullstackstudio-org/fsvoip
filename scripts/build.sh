#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Local CI for the iOS app. Runs on a Mac with Xcode (and XcodeGen: `brew install xcodegen`).
#
#   scripts/build.sh              checks + generate + build + all tests on a simulator
#   scripts/build.sh checks       only the fast checks (imports, contract fixtures, macOS package tests)
#   scripts/build.sh build        generate + build (no tests)
#   scripts/build.sh test         generate + build + tests
#
# Environment:  SIMULATOR   simulator name for the tests (default: iPhone 17)
#               LOG_DIR     where xcodebuild logs go (default: build/logs)
#
# Signing is not needed: the simulator build runs with CODE_SIGNING_ALLOWED=NO. For a device build, copy
# ios/Local.xcconfig.example to ios/Local.xcconfig first.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SIMULATOR="${SIMULATOR:-iPhone 17}"
LOG_DIR="${LOG_DIR:-$ROOT/build/logs}"
MODE="${1:-all}"

mkdir -p "$LOG_DIR"

step() { printf '\n==> %s\n' "$*"; }

run_logged() {
    # run_logged <logfile> <command...>: quiet on success, last lines on failure.
    local log="$1"; shift
    if "$@" > "$log" 2>&1; then
        return 0
    fi
    echo "FAILED: $*" >&2
    grep -E 'error:|\*\* .* FAILED \*\*|Test Case .* failed' "$log" | sort -u | head -40 >&2 || true
    echo "full log: $log" >&2
    return 1
}

checks() {
    step "Architecture check: SIP stack only inside LinphoneEngine"
    "$ROOT/scripts/check-imports.sh" --self-test
    "$ROOT/scripts/check-imports.sh"

    step "Contract: shared/fixtures against openapi.yaml and push-payload.schema.json"
    if command -v bun > /dev/null 2>&1; then
        (cd "$ROOT/scripts" && bun install --frozen-lockfile > /dev/null 2>&1 || bun install > /dev/null 2>&1)
        bun "$ROOT/scripts/validate-contract.ts" | tail -3
    else
        echo "bun not found: skipping fixture validation (install bun to run it; CI does)"
    fi

    step "Package tests on the Mac (Core, SipEngine, Pairing, Contacts)"
    for package in Core SipEngine Pairing Contacts; do
        run_logged "$LOG_DIR/swift-test-$package.log" bash -c "cd '$ROOT/ios/Packages/$package' && swift test"
        printf '    %-12s %s\n' "$package" "$(grep -E 'Executed [0-9]+ tests' "$LOG_DIR/swift-test-$package.log" | tail -1 | sed 's/^[[:space:]]*//')"
    done
}

generate() {
    step "XcodeGen"
    command -v xcodegen > /dev/null 2>&1 || { echo "xcodegen not found: brew install xcodegen" >&2; exit 1; }
    (cd "$ROOT/ios" && xcodegen generate --quiet)
}

build() {
    step "Build FSVoip for the iOS Simulator (unsigned)"
    run_logged "$LOG_DIR/xcodebuild-build.log" \
        xcodebuild -project "$ROOT/ios/FSVoip.xcodeproj" -scheme FSVoip \
        -destination 'generic/platform=iOS Simulator' \
        CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
    grep -E '\*\* BUILD' "$LOG_DIR/xcodebuild-build.log" | tail -1
}

tests() {
    step "Tests on the simulator ($SIMULATOR)"
    run_logged "$LOG_DIR/xcodebuild-test.log" \
        xcodebuild -project "$ROOT/ios/FSVoip.xcodeproj" -scheme FSVoip \
        -destination "platform=iOS Simulator,name=$SIMULATOR,OS=latest" \
        CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO test
    grep -E 'Executed [0-9]+ tests' "$LOG_DIR/xcodebuild-test.log" | tail -1 | sed 's/^[[:space:]]*//'
    grep -E '\*\* TEST' "$LOG_DIR/xcodebuild-test.log" | tail -1
}

case "$MODE" in
    checks) checks ;;
    build) checks; generate; build ;;
    test) generate; tests ;;
    all) checks; generate; build; tests ;;
    *) echo "usage: $0 [all|checks|build|test]" >&2; exit 2 ;;
esac

printf '\nDone.\n'
