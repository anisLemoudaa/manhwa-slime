#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/eu/kanade/tachiyomi/novel/NovelSectionScreen.kt"

cp "$FILE" "/tmp/NovelSectionScreen.kt.backup"

python3 - "$FILE" <<'PY'
from pathlib import Path
import re
import sys

p = Path(sys.argv[1])
s = p.read_text()

pattern = re.compile(
    r'''(?ms)^(\s*)modifier\s*=\s*Modifier\.clickable\s*\{\s*
        navigator\.push\(\s*
        NovelReaderScreen\(
            .*?
        \)\s*
        \)\s*,\s*
        \},\s*
        headlineContent\s*=\s*\{''',
)

m = pattern.search(s)

if not m:
    raise SystemExit("ERROR: Could not find the broken NovelReaderScreen navigation block.")

indent = m.group(1)

replacement = f'''{indent}modifier = Modifier.clickable {{
{indent}    val reader = NovelReaderScreen(
{indent}        sourceId = sourceId,
{indent}        novelPath = d.path,
{indent}        novelName = d.name ?: fallbackName,
{indent}        novelCover = d.cover,
{indent}        chapters = d.chapters.orEmpty(),
{indent}        chapterIndex = index,
{indent}    )
{indent}    navigator.push(reader)
{indent}}},
{indent}headlineContent = {{'''

s = s[:m.start()] + replacement + s[m.end():]

# Remove trailing whitespace / blank lines at EOF.
s = s.rstrip() + "\n"

p.write_text(s)
print("OK: NovelReaderScreen navigation block repaired.")
PY

echo
echo "===== CHECK 1: NAVIGATION ====="
nl -ba "$FILE" | sed -n '550,580p'

echo
echo "===== CHECK 2: DIFF ====="
git diff --check

echo
echo "===== CHECK 3: KOTLIN COMPILE ====="
./gradlew :app:compileDebugKotlin --no-daemon

echo
echo "========================================"
echo "BUILD PASSED: Novel reader navigation"
echo "========================================"
