bash msl_fix_font.sh#!/bin/bash
# إصلاح تعارض الاسم MslFont (مع الخط القديم في MslTranslate.kt)
cd "$(dirname "$0")" || exit 1
K=app/src/main/java/eu/kanade/tachiyomi/mslime
for f in "$K/MslTheme.kt" "$K/MslComments.kt"; do
  [ -f "$f" ] || { echo "[!!] $f not found"; exit 1; }
  sed -i 's/\bMslFont\b/MslUiFont/g' "$f"
done
grep -c "MslUiFont" "$K/MslTheme.kt" "$K/MslComments.kt"
grep -n "MslFont" "$K/MslTheme.kt" "$K/MslComments.kt" && echo "[!!] old name still present" || echo "[ok] renamed to MslUiFont"
if [ -z "$SKIP_GIT" ]; then
  git add -A && git commit -m "Fix MslFont name conflict" && git push && echo "[ok] pushed"
fi
