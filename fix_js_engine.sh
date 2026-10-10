#!/usr/bin/env bash
set -euo pipefail

FILE="core/common/src/main/kotlin/eu/kanade/tachiyomi/network/JavaScriptEngine.kt"

python3 - <<'PY'
from pathlib import Path

p = Path("core/common/src/main/kotlin/eu/kanade/tachiyomi/network/JavaScriptEngine.kt")
s = p.read_text()

if "import kotlinx.coroutines.Dispatchers" not in s:
    s = s.replace(
        "import dev.zacsweers.metro.SingleIn\n",
        "import dev.zacsweers.metro.SingleIn\nimport kotlinx.coroutines.Dispatchers\n",
    )

s = s.replace(
    "suspend fun <T> evaluate(script: String): T = withIOContext {\n"
    "        QuickJs.create().use {\n"
    "            it.evaluate(script) as T\n"
    "        }\n"
    "    }",
    "suspend inline fun <reified T> evaluate(script: String): T = withIOContext {\n"
    "        QuickJs.create(jobDispatcher = Dispatchers.Default).use {\n"
    "            it.evaluate<T>(script)\n"
    "        }\n"
    "    }",
)

p.write_text(s)
PY

git diff --check

./gradlew :core:common:compileDebugKotlin --no-daemon --console=plain
