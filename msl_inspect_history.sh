#!/usr/bin/env bash
set -euo pipefail

echo "========================================"
echo "     MANHWA SLIME - HISTORY INSPECT"
echo "========================================"

echo
echo "===== 1. HISTORY FILES ====="
find app data domain presentation-core presentation-widget core \
  -type f \( \
    -iname '*history*.kt' \
    -o -iname '*history*.sq' \
    -o -iname '*history*.sql' \
  \) 2>/dev/null | sort

echo
echo "===== 2. HISTORY REFERENCES ====="
grep -RniE \
  --include='*.kt' \
  --include='*.sq' \
  --include='*.sql' \
  'HistoryRepository|HistoryViewModel|HistoryUiModel|historyRepository|history_view|history' \
  app data domain presentation-core presentation-widget core 2>/dev/null \
  | head -n 250 || true

echo
echo "===== 3. PROFILE ====="
find app presentation-core presentation-widget \
  -type f \( -iname '*Profile*.kt' -o -iname '*profile*.kt' \) \
  2>/dev/null | sort

echo
echo "===== 4. NOVEL HISTORY ====="
if [ -f app/src/main/java/eu/kanade/tachiyomi/novel/NovelHistoryStore.kt ]; then
    nl -ba app/src/main/java/eu/kanade/tachiyomi/novel/NovelHistoryStore.kt
else
    echo "NovelHistoryStore.kt NOT FOUND"
fi

echo
echo "===== 5. GIT STATUS ====="
git status --short

echo
echo "========================================"
echo "INSPECTION COMPLETE"
echo "========================================"
