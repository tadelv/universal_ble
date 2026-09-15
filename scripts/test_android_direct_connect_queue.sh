#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
"${KOTLINC:-kotlinc}" \
  "$root/android/src/main/kotlin/com/navideck/universal_ble/AndroidDirectConnectQueue.kt" \
  "$root/android/src/test/kotlin/com/navideck/universal_ble/AndroidDirectConnectQueueScenarios.kt" \
  -jvm-target 1.8 -include-runtime -d "$build/admission-tests.jar"
java -jar "$build/admission-tests.jar"
