#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

HOME_FILE="app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt"
SOURCES_FILE="app/src/main/java/eu/kanade/presentation/browse/SourcesScreen.kt"

test -f "$HOME_FILE"
test -f "$SOURCES_FILE"

python3 - "$HOME_FILE" "$SOURCES_FILE" <<'PY'
from pathlib import Path
import sys

home = Path(sys.argv[1])
sources = Path(sys.argv[2])

def add_import(text: str, imp: str, anchor: str) -> str:
    if imp in text:
        return text
    marker = anchor
    if marker not in text:
        raise SystemExit(f"ERROR: import anchor not found: {marker}")
    return text.replace(marker, marker + "\n" + imp, 1)

h = home.read_text(encoding="utf-8")

# Compose animation state APIs live in animation.core, not androidx.compose.animation.
for imp in [
    "import androidx.compose.animation.core.animateColorAsState",
    "import androidx.compose.animation.core.animateDpAsState",
    "import androidx.compose.animation.core.animateFloatAsState",
]:
    h = add_import(h, imp, "import androidx.compose.animation.AnimatedContent")

# State APIs.
for imp in [
    "import androidx.compose.runtime.getValue",
    "import androidx.compose.runtime.mutableStateOf",
    "import androidx.compose.runtime.remember",
    "import androidx.compose.runtime.setValue",
]:
    # getValue may already exist; the helper skips duplicates.
    h = add_import(h, imp, "import androidx.compose.runtime.LaunchedEffect")

# Layout APIs.
for imp in [
    "import androidx.compose.foundation.layout.Row",
    "import androidx.compose.foundation.layout.weight",
]:
    h = add_import(h, imp, "import androidx.compose.foundation.layout.Modifier" if False else "import androidx.compose.foundation.layout.Arrangement")

# The previous patch already uses these APIs.
# Ensure the foundation imports used by the floating bar exist.
for imp in [
    "import androidx.compose.foundation.background",
    "import androidx.compose.foundation.clickable",
    "import androidx.compose.foundation.layout.Box",
    "import androidx.compose.foundation.layout.Column",
    "import androidx.compose.foundation.layout.fillMaxSize",
    "import androidx.compose.foundation.layout.fillMaxWidth",
    "import androidx.compose.foundation.layout.height",
    "import androidx.compose.foundation.layout.offset",
    "import androidx.compose.foundation.layout.padding",
    "import androidx.compose.foundation.layout.size",
    "import androidx.compose.foundation.shape.RoundedCornerShape",
]:
    h = add_import(h, imp, "import androidx.compose.foundation.layout.Arrangement")

# Graphics / text APIs.
for imp in [
    "import androidx.compose.ui.draw.clip",
    "import androidx.compose.ui.graphics.Color",
    "import androidx.compose.ui.graphics.graphicsLayer",
    "import androidx.compose.ui.text.font.FontWeight",
    "import androidx.compose.ui.unit.sp",
]:
    h = add_import(h, imp, "import androidx.compose.ui.Modifier")

home.write_text(h, encoding="utf-8")

s = sources.read_text(encoding="utf-8")

old = """contentPadding = contentPadding + PaddingValues(
              horizontal = 8.dp,
              top = 12.dp,
              bottom = 16.dp,
          ) + topSmallPaddingValues,"""
new = """contentPadding = contentPadding + PaddingValues(
              start = 8.dp,
              top = 12.dp,
              end = 8.dp,
              bottom = 16.dp,
          ) + topSmallPaddingValues,"""

if old in s:
    s = s.replace(old, new, 1)
elif "horizontal = 8.dp" in s and "top = 12.dp" in s and "bottom = 16.dp" in s:
    s = s.replace("horizontal = 8.dp", "start = 8.dp,\\n              end = 8.dp", 1)
else:
    raise SystemExit("ERROR: the invalid PaddingValues block was not found in SourcesScreen.kt")

sources.write_text(s, encoding="utf-8")
PY

echo
echo "✅ Build fixes applied."
echo
git diff --check
echo
echo "Changed files:"
git diff --name-only
echo
echo "Important: this script only fixes the compilation errors. It does not commit or push."
