#!/bin/bash
# نظام الرتب (F إلى SS+) داخل شاشة Statistics
cd "$(dirname "$0")" || exit 1
D=app/src/main/java/eu/kanade/presentation/more/stats
[ -f "$D/StatsScreenContent.kt" ] || { echo "[!!] StatsScreenContent.kt not found"; exit 1; }
[ -f app/src/main/res/drawable-nodpi/ic_mihon.png ] || echo "[!!] slime logo missing: run 'bash msl_logo.sh' first"

cat > "$D/RankSection.kt" <<'EOF'
package eu.kanade.presentation.more.stats

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.more.stats.data.StatsData
import eu.kanade.tachiyomi.R

private data class ReaderRank(val name: String, val min: Int, val title: String)

private val readerRanks = listOf(
    ReaderRank("F", 0, "مبتدئ"),
    ReaderRank("E", 50, "متدرّب"),
    ReaderRank("D", 150, "قارئ"),
    ReaderRank("C", 350, "قارئ ماهر"),
    ReaderRank("B", 700, "محترف"),
    ReaderRank("A", 1200, "خبير"),
    ReaderRank("S", 2000, "أسطورة"),
    ReaderRank("SS", 3500, "ملك السلايم"),
    ReaderRank("SS+", 5500, "إمبراطور السلايم"),
)

@Composable
fun RankSection(chapters: StatsData.Chapters) {
    val read = chapters.readChapterCount
    val index = readerRanks.indexOfLast { read >= it.min }.coerceAtLeast(0)
    val rank = readerRanks[index]
    val next = readerRanks.getOrNull(index + 1)
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_mihon),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "رتبتك: ${rank.name}",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(text = rank.title, style = MaterialTheme.typography.titleMedium)
                Text(text = "الفصول المقروءة: $read", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (next != null) {
            LinearProgressIndicator(
                progress = { (read - rank.min).toFloat() / (next.min - rank.min) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Text(
                text = "باقي ${next.min - read} فصل للوصول إلى الرتبة ${next.name}",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(text = "وصلت إلى أعلى رتبة 👑", modifier = Modifier.padding(16.dp))
        }
    }
}
EOF

python3 - <<'EOF'
import re
p='app/src/main/java/eu/kanade/presentation/more/stats/StatsScreenContent.kt'
s=open(p).read()
if 'RankSection(' in s:
    print('[ok] already patched'); raise SystemExit
m=re.search(r'\n([ \t]*)item \{',s)
if not m:
    print('[!!] item block not found in StatsScreenContent.kt'); raise SystemExit(1)
ind=m.group(1)
ins='\n%sitem {\n%s    RankSection(state.chapters)\n%s}'%(ind,ind,ind)
s=s[:m.start()]+ins+s[m.start():]
open(p,'w').write(s)
i=s.index('RankSection(')
print('[ok] patched near:'); print(s[max(0,i-120):i+160])
EOF

if [ -z "$SKIP_GIT" ]; then
  git add -A && git commit -m "Add reader rank card to Statistics" && git push && echo "[ok] pushed"
fi
