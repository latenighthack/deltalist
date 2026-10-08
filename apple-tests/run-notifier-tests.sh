#!/bin/bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/.." && pwd)
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/deltalist-notifier-tests.XXXXXX")
trap 'rm -rf "$test_dir"' EXIT
# Compile the production reconciler itself, without requesting system notification permission.
# The binary framework keeps these implementation types internal. Extract complete declaration
# sections so these tests cannot accidentally maintain a second copy of the implementation.
python3 - "$repo_root" "$test_dir" <<'PY'
from pathlib import Path
import sys
root, output = map(Path, sys.argv[1:])
source = (root / 'deltalist-core/src/commonMain/swift/DeltaNotifier.swift').read_text()
sink = source[source.index('@available(iOS 14.0, *)\nfinal class NotificationSink'):source.index('/// Production sink')]
controller = source[source.index('/// One tracked tray entry.'):source.index('// MARK: - Notifier')]
(output / 'Reconciler.swift').write_text('import Foundation\n' + sink + controller)
PY
xcrun swiftc -parse-as-library "$test_dir/Reconciler.swift" \
    "$repo_root/apple-tests/NotifierTests/NotifierRegressionTests.swift" -o "$test_dir/notifier-tests"
"$test_dir/notifier-tests"
