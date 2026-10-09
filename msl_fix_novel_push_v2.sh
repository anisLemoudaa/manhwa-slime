#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/eu/kanade/tachiyomi/novel/NovelSectionScreen.kt"

cp "$FILE" "/tmp/NovelSectionScreen.kt.before_push_fix"

python3 - "$FILE" <<'PY'
from pathlib import Path
import sys

p = Path(sys.argv[1])
lines = p.read_text().splitlines()

# Find the chapter ListItem block.
items_idx = next(
    (i for i, line in enumerate(lines)
     if "itemsIndexed(d.chapters.orEmpty())" in line),
    None,
)

if items_idx is None:
    raise SystemExit("ERROR: itemsIndexed chapter block not found.")

# Find the clickable modifier belonging to that ListItem.
click_idx = next(
    (i for i in range(items_idx, min(items_idx + 15, len(lines)))
     if "modifier = Modifier.clickable {" in lines[i]),
    None,
)

if click_idx is None:
    raise SystemExit("ERROR: chapter clickable block not found.")

# Find headlineContent, which marks the end of the clickable body.
headline_idx = next(
    (i for i in range(click_idx + 1, min(click_idx + 30, len(lines)))
     if "headlineContent = {" in lines[i]),
    None,
)

if headline_idx is None:
    raise SystemExit("ERROR: headlineContent boundary not found.")

indent = lines[click_idx].split("modifier")[0]

new_body = [
    f"{indent}    val reader = NovelReaderScreen(",
    f"{indent}        sourceId = sourceId,",
    f"{indent}        novelPath = d.path,",
    f"{indent}        novelName = d.name ?: fallbackName,",
    f"{indent}        novelCover = d.cover,",
    f"{indent}        chapters = d.chapters.orEmpty(),",
    f"{indent}        chapterIndex = index,",
    f"{indent}    )",
    f"{indent}    navigator.push(reader)",
    f"{indent}}},",
]

# Keep the clickable modifier line and headlineContent untouched.
lines = lines[:click_idx + 1] + new_body + lines[headline_idx:]

p.write_text("\n".join(lines) + "\n")

print("OK: replaced the entire broken clickable navigation body.")
PY

echo
echo "===== REPAIRED BLOCK ====="
nl -ba "$FILE" | sed -n '552,578p'

echo
echo "===== DIFF CHECK ====="
git diff --check

echo
echo "===== COMPILE ====="
./gradlew :app:compileDebugKotlin --no-daemon

echo
echo "================================"
echo "BUILD PASSED"
echo "================================"
