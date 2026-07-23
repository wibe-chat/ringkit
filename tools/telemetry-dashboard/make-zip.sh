#!/usr/bin/env bash
# Builds ringkit-dashboard-netlify.zip (drag-and-drop deployable) with netlify.toml
# at the archive root — exactly what Netlify's manual "drag and drop" deploy expects.
set -euo pipefail
cd "$(dirname "$0")"
out="ringkit-dashboard-netlify.zip"
rm -f "$out"
zip -r "$out" netlify.toml netlify public package.json -x '*.DS_Store' >/dev/null
echo "wrote $(pwd)/$out"
