#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Xcode Cloud hook (used later, when builds move off the developer's Mac): generate the Xcode project, which is
# not committed. Xcode Cloud runs this after cloning. No secrets are needed or read here; signing is configured in
# Xcode Cloud itself.
set -eu

cd "$CI_PRIMARY_REPOSITORY_PATH/ios"

if ! command -v xcodegen > /dev/null 2>&1; then
    brew install xcodegen
fi

xcodegen generate
