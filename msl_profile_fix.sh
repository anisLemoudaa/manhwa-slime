#!/bin/bash
# إصلاح أخطاء الترجمة في ProfileTab.kt (مكتبة الأيقونات غير موجودة في Mihon)
cd "$(dirname "$0")" || exit 1
F=app/src/main/java/eu/kanade/tachiyomi/ui/profile/ProfileTab.kt
[ -f "$F" ] || { echo "[!!] $F not found: run msl_profile.sh first"; exit 1; }
python3 - <<'EOF'
p='app/src/main/java/eu/kanade/tachiyomi/ui/profile/ProfileTab.kt'
s=open(p).read()
lines=[l for l in s.split('\n') if 'import androidx.compose.material.icons' not in l and 'import androidx.compose.ui.graphics.vector.rememberVectorPainter' not in l]
s='\n'.join(lines)
n=s.count('rememberVectorPainter(Icons.Outlined.Person)')
s=s.replace('rememberVectorPainter(Icons.Outlined.Person)','painterResource(R.drawable.ic_mihon)')
for imp in ('import androidx.compose.ui.res.painterResource','import eu.kanade.tachiyomi.R'):
    if imp not in s:
        s=s.replace('import java.io.File',imp+'\nimport java.io.File',1)
s=s.replace('.background(Color(0xFF2DB8FD))','.background(Color(0xFF0F0F13))')
open(p,'w').write(s)
print('[ok] replaced icon usages:',n)
EOF
grep -n "material.icons\|rememberVectorPainter" "$F" && echo "[!!] icons still referenced" || echo "[ok] no icons library references left"
if [ -z "$SKIP_GIT" ]; then
  git add -A && git commit -m "Fix profile tab icons" && git push && echo "[ok] pushed"
fi
