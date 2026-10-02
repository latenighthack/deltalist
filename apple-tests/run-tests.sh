#!/bin/bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"
platform=${1:-macos}
case "$platform" in
  macos)
    target=MacosArm64
    scheme=LifecycleMac
    destination='platform=macOS,arch=arm64'
    ;;
  ios)
    target=IosSimulatorArm64
    scheme=LifecycleIOS
    destination=${DELTALIST_IOS_DESTINATION:?Set DELTALIST_IOS_DESTINATION to an explicit iOS simulator destination}
    ;;
  *) echo 'Usage: apple-tests/run-tests.sh macos|ios' >&2; exit 2 ;;
esac
./gradlew ":deltalist-core:linkDebugFramework${target}" ":demo-core:linkDebugFramework${target}"
mkdir -p apple-tests/build
xcodegen --spec apple-tests/project.yml --project apple-tests/build
result_dir=$(mktemp -d "$repo_root/apple-tests/build/run-${platform}.XXXXXX")
xcodebuild test -project apple-tests/build/DeltaListLifecycle.xcodeproj \
  -scheme "$scheme" -destination "$destination" \
  -derivedDataPath "apple-tests/build/DerivedData-${platform}" \
  -resultBundlePath "$result_dir/Tests.xcresult"
