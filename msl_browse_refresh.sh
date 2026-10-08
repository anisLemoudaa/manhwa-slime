#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"ERROR: {label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)

# 1) Sources screen: modern grouped source cards + cleaner language headers.
path = Path("app/src/main/java/eu/kanade/presentation/browse/SourcesScreen.kt")
text = path.read_text()
original = text

text = replace_once(
    text,
    "import androidx.compose.foundation.layout.Column\nimport androidx.compose.foundation.layout.PaddingValues\nimport androidx.compose.foundation.layout.fillMaxWidth\nimport androidx.compose.foundation.layout.padding\n",
    "import androidx.compose.foundation.layout.Column\nimport androidx.compose.foundation.layout.PaddingValues\nimport androidx.compose.foundation.layout.Row\nimport androidx.compose.foundation.layout.Spacer\nimport androidx.compose.foundation.layout.fillMaxWidth\nimport androidx.compose.foundation.layout.padding\n",
    "SourcesScreen layout imports",
)
text = replace_once(
    text,
    "import androidx.compose.material3.AlertDialog\nimport androidx.compose.material3.Icon\n",
    "import androidx.compose.material3.AlertDialog\nimport androidx.compose.material3.HorizontalDivider\nimport androidx.compose.material3.Icon\n",
    "HorizontalDivider import",
)
text = replace_once(
    text,
    "import androidx.compose.material3.IconButton\nimport androidx.compose.material3.LocalTextStyle\n",
    "import androidx.compose.material3.IconButton\nimport androidx.compose.material3.LocalTextStyle\n",
    "SourcesScreen icon imports",
)
text = replace_once(
    text,
    "import androidx.compose.material3.TextButton\nimport androidx.compose.runtime.Composable\n",
    "import androidx.compose.material3.TextButton\nimport androidx.compose.material3.Surface\nimport androidx.compose.runtime.Composable\n",
    "Surface import",
)
text = replace_once(
    text,
    "import androidx.compose.ui.unit.dp\n",
    "import androidx.compose.ui.text.font.FontWeight\nimport androidx.compose.ui.unit.dp\nimport androidx.compose.foundation.shape.RoundedCornerShape\n",
    "shape/font imports",
)
text = replace_once(
    text,
    """            ScrollbarLazyColumn(
                contentPadding = contentPadding + topSmallPaddingValues,
            ) {""",
    """            ScrollbarLazyColumn(
                contentPadding = contentPadding + PaddingValues(
                    horizontal = 8.dp,
                    top = 12.dp,
                    bottom = 16.dp,
                ) + topSmallPaddingValues,
            ) {""",
    "Sources list padding",
)

header_start = text.index("@Composable\nprivate fun SourceHeader(")
header_end = text.index("\n@Composable\nprivate fun SourceItem(", header_start)
new_header = '''@Composable
private fun SourceHeader(
    language: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier.padding(
            horizontal = MaterialTheme.padding.medium,
            vertical = 10.dp,
        ),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = LocaleHelper.getSourceDisplayName(language, context),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.width(12.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
'''
text = text[:header_start] + new_header + text[header_end:]

item_start = text.index("@Composable\nprivate fun SourceItem(")
item_end = text.index("\n@Composable\nprivate fun SourcePinButton(", item_start)
new_item = '''@Composable
private fun SourceItem(
    source: Source,
    onClickItem: (Source, Listing) -> Unit,
    onLongClickItem: (Source) -> Unit,
    onClickPin: (Source) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
    ) {
        BaseSourceItem(
            source = source,
            onClickItem = { onClickItem(source, Listing.Popular) },
            onLongClickItem = { onLongClickItem(source) },
            action = {
                if (source.supportsLatest) {
                    TextButton(onClick = { onClickItem(source, Listing.Latest) }) {
                        Text(
                            text = stringResource(MR.strings.latest),
                            style = LocalTextStyle.current.copy(
                                color = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
                SourcePinButton(
                    isPinned = Pin.Pinned in source.pin,
                    onClick = { onClickPin(source) },
                )
            },
        )
    }
}
'''
text = text[:item_start] + new_item + text[item_end:]

if text == original:
    raise SystemExit("ERROR: SourcesScreen was not changed")
path.write_text(text)
print(f"updated {path}")

# 2) Manga grids/list: harmonize outer spacing with the refreshed Library grid.
for raw_path, old, new in [
    (
        "app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceComfortableGrid.kt",
        "contentPadding = contentPadding + PaddingValues(8.dp),",
        "contentPadding = contentPadding + PaddingValues(12.dp),",
    ),
    (
        "app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceCompactGrid.kt",
        "contentPadding = contentPadding + PaddingValues(8.dp),",
        "contentPadding = contentPadding + PaddingValues(12.dp),",
    ),
    (
        "app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceList.kt",
        "contentPadding = contentPadding + PaddingValues(vertical = 8.dp),",
        "contentPadding = contentPadding + PaddingValues(horizontal = 4.dp, vertical = 12.dp),",
    ),
]:
    p = Path(raw_path)
    t = p.read_text()
    t2 = replace_once(t, old, new, raw_path)
    p.write_text(t2)
    print(f"updated {p}")
PY

echo
echo "=== git diff --check ==="
git diff --check

echo
echo "=== Browse refresh diff ==="
git diff -- app/src/main/java/eu/kanade/presentation/browse/SourcesScreen.kt \
  app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceComfortableGrid.kt \
  app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceCompactGrid.kt \
  app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceList.kt
