#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt"

echo "🔧 إصلاح remember في HomeScreen.kt..."

python3 - "$FILE" <<'PY'
from pathlib import Path
import re
import sys

p = Path(sys.argv[1])
s = p.read_text(encoding="utf-8")

# Add the correct Compose Runtime import if it is missing.
if "import androidx.compose.runtime.remember" not in s:
    marker = "import androidx.compose.runtime.produceState"
    if marker in s:
        s = s.replace(
            marker,
            marker + "\nimport androidx.compose.runtime.remember",
            1,
        )
    else:
        # Fallback: insert after the last androidx.compose.runtime import.
        matches = list(re.finditer(r"^import androidx\.compose\.runtime\..*$", s, re.M))
        if matches:
            pos = matches[-1].end()
            s = s[:pos] + "\nimport androidx.compose.runtime.remember" + s[pos:]
        else:
            raise SystemExit("❌ لم أجد مكانًا آمنًا لإضافة import remember")

# Also fully qualify remember calls so compilation does not depend on import resolution.
# Do not touch an already-qualified call.
s = re.sub(
    r"(?<![\w.])remember\s*(?=\()",
    "androidx.compose.runtime.remember",
    s,
)

p.write_text(s, encoding="utf-8")
PY

echo
echo "✅ تم تثبيت remember في HomeScreen.kt"
echo "🔎 فحص whitespace:"
git diff --check

echo
echo "🔍 تحقق سريع من remember:"
grep -n "remember" "$FILE" | head -20 || true

echo
echo "✅ انتهى الإصلاح. لم يتم commit أو push."
