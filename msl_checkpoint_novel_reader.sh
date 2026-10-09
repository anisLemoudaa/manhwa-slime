#!/usr/bin/env bash
set -euo pipefail

echo "===== CURRENT STATUS ====="
git status --short

echo
echo "===== DIFF CHECK ====="
git diff --check

echo
echo "===== VERIFY NOVEL READER ====="
test -f app/src/main/java/eu/kanade/tachiyomi/novel/NovelSectionScreen.kt
grep -n "val reader = NovelReaderScreen" \
  app/src/main/java/eu/kanade/tachiyomi/novel/NovelSectionScreen.kt

echo
echo "===== VERIFY BUILD ====="
./gradlew :app:compileDebugKotlin --no-daemon

echo
echo "===== STAGING ONLY NOVEL READER CHANGES ====="

git add app/src/main/java/eu/kanade/tachiyomi/novel/NovelSectionScreen.kt

if [ -f app/src/main/java/eu/kanade/tachiyomi/novel/NovelHistoryStore.kt ]; then
    git add app/src/main/java/eu/kanade/tachiyomi/novel/NovelHistoryStore.kt
fi

echo
echo "===== STAGED FILES ====="
git diff --cached --name-status

echo
echo "===== COMMIT ====="
git commit -m "Fix novel reader navigation and history checkpoint"

echo
echo "===== FINAL STATUS ====="
git status --short

echo
echo "=========================================="
echo "CHECKPOINT CREATED SUCCESSFULLY"
echo "=========================================="
