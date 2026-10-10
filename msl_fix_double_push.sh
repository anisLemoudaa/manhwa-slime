#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/eu/kanade/tachiyomi/novel/NovelSectionScreen.kt"

echo "🐾 Removing duplicated navigator.push..."

python3 - "$FILE" <<'PY'
from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text()

old = """navigator.push(
                    navigator.push(
                        NovelReaderScreen("""
new = """navigator.push(
                    NovelReaderScreen("""

if old not in s:
    raise SystemExit("❌ Expected duplicated push block was not found.")

s = s.replace(old, new, 1)

p.write_text(s)
print("✅ Duplicate navigator.push removed.")
PY

echo
echo "===== CALL SITE ====="
nl -ba "$FILE" | sed -n '555,575p'

echo
echo "===== DIFF CHECK ====="
git diff --check || true

echo
echo "===== BUILD ====="
./gradlew :app:compileDebugKotlin --no-daemon

echo
echo "🎉 BUILD PASSED"
