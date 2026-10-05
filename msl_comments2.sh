#!/bin/bash
# التعليقات (الجزء 2): زر "التعليقات" في صف الأزرار بصفحة المانهوا
cd "$(dirname "$0")" || exit 1
K=app/src/main/java/eu/kanade/tachiyomi/mslime
H=app/src/main/java/eu/kanade/presentation/manga/components/MangaInfoHeader.kt
S=app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt
[ -f "$K/MslComments.kt" ] || { echo "[!!] MslComments.kt missing: run msl_comments.sh first"; exit 1; }
[ -f "$H" ] && [ -f "$S" ] || { echo "[!!] MangaInfoHeader.kt or MangaScreen.kt not found"; exit 1; }

cat > $K/MslIcons.kt <<'EOF'
package eu.kanade.tachiyomi.mslime

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val MslChatIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MslChat",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(20f, 2f)
            horizontalLineTo(4f)
            curveTo(2.9f, 2f, 2f, 2.9f, 2f, 4f)
            verticalLineTo(22f)
            lineTo(6f, 18f)
            horizontalLineTo(20f)
            curveTo(21.1f, 18f, 22f, 17.1f, 22f, 16f)
            verticalLineTo(4f)
            curveTo(22f, 2.9f, 21.1f, 2f, 20f, 2f)
            close()
        }
    }.build()
}
EOF

python3 - <<'EOF'
import re
h='app/src/main/java/eu/kanade/presentation/manga/components/MangaInfoHeader.kt'
s=open(h).read()
if 'mslTitle' in s:
    print('[ok] MangaInfoHeader already patched')
else:
    m=re.search(r'fun MangaActionRow\((.*?)\n\) \{',s,re.S)
    if not m: print('[!!] MangaActionRow signature not found'); raise SystemExit(1)
    sig=m.group(1)
    if re.search(r'\n(\s*)modifier: Modifier = Modifier,',sig):
        newsig=re.sub(r'\n(\s*)modifier: Modifier = Modifier,',r'\n\1mslTitle: String = "",\n\1modifier: Modifier = Modifier,',sig,count=1)
    else:
        newsig=sig.rstrip()+'\n    mslTitle: String = "",'
    s=s[:m.start(1)]+newsig+s[m.end(1):]
    start=s.index('fun MangaActionRow(')
    mm=re.search(r'\n([ \t]*)if \(onWebViewClicked != null\) \{',s[start:])
    if not mm: print('[!!] WebView button block not found'); raise SystemExit(1)
    ind=mm.group(1)
    block=('\n'+ind+'if (mslTitle.isNotEmpty()) {\n'
      +ind+'    val showMsl = remember { androidx.compose.runtime.mutableStateOf(false) }\n'
      +ind+'    MangaActionButton(\n'
      +ind+'        title = "التعليقات",\n'
      +ind+'        icon = eu.kanade.tachiyomi.mslime.MslChatIcon,\n'
      +ind+'        color = defaultActionButtonColor,\n'
      +ind+'        onClick = { showMsl.value = true },\n'
      +ind+'    )\n'
      +ind+'    if (showMsl.value) {\n'
      +ind+'        eu.kanade.tachiyomi.mslime.MslCommentsDialog(title = mslTitle, onDismiss = { showMsl.value = false })\n'
      +ind+'    }\n'
      +ind+'}')
    pos=start+mm.start()
    s=s[:pos]+block+s[pos:]
    open(h,'w').write(s)
    print('[ok] MangaInfoHeader patched')
p='app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt'
t=open(p).read()
if 'mslTitle' in t:
    print('[ok] MangaScreen already patched')
else:
    t,n=re.subn(r'(\n([ \t]*)onEditCategory = onEditCategoryClicked,)',r'\1\n\2mslTitle = state.manga.title,',t)
    open(p,'w').write(t)
    print('[ok] MangaScreen patched call sites:',n)
    if n<2: print('[note] expected 2 call sites; the other layout may need manual patching')
EOF
if [ -z "$SKIP_GIT" ]; then
  git add -A && git commit -m "Comments button on manga page" && git push && echo "[ok] pushed"
fi
