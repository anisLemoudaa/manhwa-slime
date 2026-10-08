#!/usr/bin/env bash
set -euo pipefail

ROOT="$(pwd)"
HOME_FILE="$ROOT/app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt"
MORE_FILE="$ROOT/app/src/main/java/eu/kanade/tachiyomi/ui/more/MoreTab.kt"

echo "🔧 إصلاح أخطاء Kotlin الثلاثة..."

# 1) animateColorAsState belongs to androidx.compose.animation in this project.
python3 - "$HOME_FILE" <<'PY'
from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text()

s = s.replace(
    "import androidx.compose.animation.core.animateColorAsState",
    "import androidx.compose.animation.animateColorAsState",
)

# 2) remember is a Compose Runtime API.
if "import androidx.compose.runtime.remember" not in s:
    marker = "import androidx.compose.runtime.produceState"
    if marker in s:
        s = s.replace(
            marker,
            marker + "\nimport androidx.compose.runtime.remember",
            1,
        )
    else:
        raise SystemExit("لم أجد مكانًا آمنًا لإضافة import remember في HomeScreen.kt")

p.write_text(s)
PY

# 3) Remove the stale onClickProfile argument from MoreTab.
#    MoreScreen/MoreTab in the current branch does not expose that parameter.
python3 - "$MORE_FILE" <<'PY'
from pathlib import Path
import re
import sys

p = Path(sys.argv[1])
s = p.read_text()

s2 = re.sub(r'^\s*onClickProfile\s*=\s*\{[^\n]*\},\s*\n', '', s, flags=re.M)

# Handle a multiline lambda defensively if one exists.
if s2 == s and "onClickProfile" in s:
    s2 = re.sub(
        r'^\s*onClickProfile\s*=\s*\{.*?\n\s*\},\s*$',
        '',
        s,
        flags=re.M | re.S,
        count=1,
    )

if "onClickProfile" in s2:
    raise SystemExit("بقي onClickProfile داخل MoreTab.kt، أوقف السكربت بدل تخمين التوقيع.")

p.write_text(s2)
PY

echo
echo "✅ تم إصلاح:"
echo "   • animateColorAsState import"
echo "   • remember import"
echo "   • إزالة onClickProfile غير المدعوم من MoreTab"
echo
echo "🔎 فحص whitespace:"
git diff --check

echo
echo "📄 الملفات التي تغيرت بهذا الإصلاح:"
git status --short -- \
  app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt \
  app/src/main/java/eu/kanade/tachiyomi/ui/more/MoreTab.kt

echo
echo "✅ انتهى الإصلاح. لم يتم commit أو push."
