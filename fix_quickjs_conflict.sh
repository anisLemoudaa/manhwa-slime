cd /workspaces/manhwa-slime && ./gradlew assembleDebug --no-daemon --max-workers=1 -Dorg.gradle.parallel=false -Dkotlin.compiler.execution.strategy=in-process#!/usr/bin/env bash
set -euo pipefail

echo "🔧 Fixing QuickJS native library conflict..."

JS_FILE="core/common/src/main/kotlin/eu/kanade/tachiyomi/network/JavaScriptEngine.kt"
GRADLE_FILE="core/common/build.gradle.kts"

cp "$JS_FILE" "/tmp/JavaScriptEngine.kt.bak"
cp "$GRADLE_FILE" "/tmp/core-common-build.gradle.kts.bak"

python3 - <<'PY'
from pathlib import Path

js = Path("core/common/src/main/kotlin/eu/kanade/tachiyomi/network/JavaScriptEngine.kt")
s = js.read_text()

s = s.replace(
    "import app.cash.quickjs.QuickJs",
    "import com.dokar.quickjs.QuickJs",
)

js.write_text(s)

gradle = Path("core/common/build.gradle.kts")
s = gradle.read_text()

old = "implementation(libs.quickJs)"
new = "implementation(libs.novelQuickjs)"

if old not in s:
    raise SystemExit("❌ Could not find implementation(libs.quickJs)")

s = s.replace(old, new, 1)
gradle.write_text(s)
PY

echo "✅ JavaScriptEngine now uses Dokar QuickJS"
echo "✅ Cash QuickJS dependency removed from core:common"
echo "✅ Manga source APIs/files were not deleted"

echo
echo "===== DIFF CHECK ====="
git diff --check

echo
echo "===== QUICKJS DIFF ====="
git diff -- "$JS_FILE" "$GRADLE_FILE"

echo
echo "===== TESTING NATIVE MERGE ====="
./gradlew :app:mergeDebugNativeLibs --no-daemon --console=plain

echo
echo "✅ QuickJS native conflict is fixed."
