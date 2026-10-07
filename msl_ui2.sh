#!/bin/bash
# خط أجمل في الملف الشخصي + زر الترجمة «ع» قابل للسحب والإخفاء
cd "$(dirname "$0")" || exit 1
K=app/src/main/java/eu/kanade/tachiyomi/mslime
P=app/src/main/java/eu/kanade/tachiyomi/ui/profile/ProfileTab.kt
T=$K/MslTranslate.kt
[ -f "$P" ] && [ -f "$T" ] || { echo "[!!] ProfileTab.kt or MslTranslate.kt missing"; exit 1; }
mkdir -p app/src/main/res/font

get() { # $1 اسم الملف, $2 المسار, $3 مجلد الخط على Google Fonts
  for base in "https://raw.githubusercontent.com/google/fonts/main/ofl/$3" "https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/$3"; do
    if curl -fsSL "$base/$1" -o "$2" && [ "$(stat -c%s "$2")" -gt 20000 ]; then return 0; fi
  done
  rm -f "$2"; return 1
}
RG=app/src/main/res/font/msl_font_regular.ttf
BD=app/src/main/res/font/msl_font_bold.ttf
DS=app/src/main/res/font/msl_font_display.ttf
[ -f "$RG" ] || get Tajawal-Regular.ttf $RG tajawal
[ -f "$BD" ] || get Tajawal-Bold.ttf $BD tajawal
[ -f "$DS" ] || get Lalezar-Regular.ttf $DS lalezar
BODY=0; DISP=0
[ -f "$RG" ] && [ -f "$BD" ] && BODY=1
[ -f "$DS" ] && DISP=1
echo "fonts: body=$BODY display=$DISP"

{
echo 'package eu.kanade.tachiyomi.mslime'
echo
echo 'import androidx.compose.material3.MaterialTheme'
echo 'import androidx.compose.material3.Typography'
echo 'import androidx.compose.runtime.Composable'
echo 'import androidx.compose.ui.text.font.Font'
echo 'import androidx.compose.ui.text.font.FontFamily'
echo 'import androidx.compose.ui.text.font.FontWeight'
echo 'import eu.kanade.tachiyomi.R'
echo
if [ "$BODY" = 1 ]; then
echo 'val MslUiFont = FontFamily(Font(R.font.msl_font_regular, FontWeight.Normal), Font(R.font.msl_font_bold, FontWeight.Bold))'
else
echo 'val MslUiFont: FontFamily = FontFamily.Default'
fi
if [ "$DISP" = 1 ]; then
echo 'val MslDisplayFont = FontFamily(Font(R.font.msl_font_display, FontWeight.Normal), Font(R.font.msl_font_display, FontWeight.Bold))'
else
echo 'val MslDisplayFont: FontFamily = MslUiFont'
fi
cat <<'EOF'

fun Typography.withFonts(body: FontFamily, display: FontFamily): Typography = Typography(
    displayLarge = displayLarge.copy(fontFamily = display),
    displayMedium = displayMedium.copy(fontFamily = display),
    displaySmall = displaySmall.copy(fontFamily = display),
    headlineLarge = headlineLarge.copy(fontFamily = display),
    headlineMedium = headlineMedium.copy(fontFamily = display),
    headlineSmall = headlineSmall.copy(fontFamily = display),
    titleLarge = titleLarge.copy(fontFamily = display),
    titleMedium = titleMedium.copy(fontFamily = display),
    titleSmall = titleSmall.copy(fontFamily = display),
    bodyLarge = bodyLarge.copy(fontFamily = body),
    bodyMedium = bodyMedium.copy(fontFamily = body),
    bodySmall = bodySmall.copy(fontFamily = body),
    labelLarge = labelLarge.copy(fontFamily = body),
    labelMedium = labelMedium.copy(fontFamily = body),
    labelSmall = labelSmall.copy(fontFamily = body),
)

@Composable
fun MslThemed(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography.withFonts(MslUiFont, MslDisplayFont),
        content = content,
    )
}
EOF
} > $K/MslTheme.kt
echo "[ok] MslTheme.kt"

cat > $K/MslReaderSettings.kt <<'EOF'
package eu.kanade.tachiyomi.mslime

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private fun findActivity(ctx: Context): Activity? {
    var c: Context = ctx
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun MslReaderSettingsCard() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(MslHook.enabled(ctx)) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "زر الترجمة «ع» في القارئ", fontWeight = FontWeight.Bold)
                    Text(text = "اسحبه لأي مكان، أو أخفِه من هنا", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = on, onCheckedChange = { on = it; MslHook.setEnabled(ctx, it) })
            }
            TextButton(onClick = { findActivity(ctx)?.let { MslTranslate.askKey(it) } }) { Text("مفتاح الترجمة الذكية") }
            TextButton(
                onClick = {
                    MslHook.resetPosition(ctx)
                    Toast.makeText(ctx, "أُعيد الزر لمكانه الأصلي", Toast.LENGTH_SHORT).show()
                },
            ) { Text("إعادة الزر لمكانه الأصلي") }
        }
    }
}
EOF
echo "[ok] MslReaderSettings.kt"

python3 - <<'EOF'
import re
# 1) استبدال MslHook بنسخة قابلة للسحب والإخفاء
p='app/src/main/java/eu/kanade/tachiyomi/mslime/MslTranslate.kt'
s=open(p).read()
new_hook='''object MslHook : Application.ActivityLifecycleCallbacks {
    private const val TAG = "msl_fab"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("msl", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("fab_enabled", true)

    fun setEnabled(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("fab_enabled", v).apply()
    }

    fun resetPosition(ctx: Context) {
        prefs(ctx).edit().remove("fab_x").remove("fab_y").apply()
    }

    private fun showMenu(activity: Activity, fab: View) {
        val pm = PopupMenu(activity, fab)
        pm.menu.add(0, 1, 0, "إخفاء الزر (يُعاد من الملف الشخصي)")
        pm.menu.add(0, 2, 1, "مفتاح الترجمة الذكية")
        pm.menu.add(0, 3, 2, "إعادة الزر لمكانه")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> {
                    setEnabled(activity, false)
                    fab.visibility = View.GONE
                }
                2 -> MslTranslate.askKey(activity)
                else -> resetPosition(activity)
            }
            true
        }
        pm.show()
    }

    override fun onActivityResumed(activity: Activity) {
        if (!activity.javaClass.name.endsWith("ReaderActivity")) return
        val old = activity.window.decorView.findViewWithTag<View>(TAG)
        if (!enabled(activity)) {
            old?.visibility = View.GONE
            return
        }
        if (old != null) {
            old.visibility = View.VISIBLE
            return
        }
        val d = activity.resources.displayMetrics.density
        val prefs = prefs(activity)
        val fab = TextView(activity)
        fab.tag = TAG
        fab.text = "ع"
        fab.textSize = 20f
        fab.setTextColor(Color.WHITE)
        fab.gravity = Gravity.CENTER
        fab.alpha = 0.7f
        val shape = GradientDrawable()
        shape.shape = GradientDrawable.OVAL
        shape.setColor(0xFF2DB8FD.toInt())
        fab.background = shape
        fab.setOnClickListener { MslTranslate.run(activity, fab) }

        val handler = Handler(Looper.getMainLooper())
        var downX = 0f
        var downY = 0f
        var offX = 0f
        var offY = 0f
        var moved = false
        var longDone = false
        val longRun = Runnable {
            longDone = true
            showMenu(activity, fab)
        }
        fab.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    offX = v.x - e.rawX
                    offY = v.y - e.rawY
                    moved = false
                    longDone = false
                    handler.postDelayed(longRun, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && (Math.abs(e.rawX - downX) > 16 || Math.abs(e.rawY - downY) > 16)) {
                        moved = true
                        handler.removeCallbacks(longRun)
                    }
                    if (moved && !longDone) {
                        val p = v.parent as View
                        v.x = (e.rawX + offX).coerceIn(0f, (p.width - v.width).toFloat())
                        v.y = (e.rawY + offY).coerceIn(0f, (p.height - v.height).toFloat())
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longRun)
                    if (e.actionMasked == MotionEvent.ACTION_UP && !moved && !longDone) v.performClick()
                    if (moved) {
                        val p = v.parent as View
                        prefs.edit().putFloat("fab_x", v.x / p.width).putFloat("fab_y", v.y / p.height).apply()
                    }
                    true
                }
                else -> false
            }
        }

        val size = (48 * d).toInt()
        val lp = FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.CENTER_VERTICAL)
        lp.marginEnd = (8 * d).toInt()
        activity.addContentView(fab, lp)
        fab.post {
            val p = fab.parent as? View
            if (p != null && prefs.contains("fab_x")) {
                fab.x = (prefs.getFloat("fab_x", 0f) * p.width).coerceIn(0f, (p.width - fab.width).toFloat())
                fab.y = (prefs.getFloat("fab_y", 0f) * p.height).coerceIn(0f, (p.height - fab.height).toFloat())
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

object MslFont'''
if 'fun resetPosition' in s:
    print('[ok] MslHook already patched')
else:
    m=re.search(r'object MslHook : Application\.ActivityLifecycleCallbacks \{.*?\n\}\n\nobject MslFont',s,re.S)
    if not m:
        print('[!!] MslHook block not found in MslTranslate.kt (run msl_ai_gemini.sh first?)'); raise SystemExit(1)
    s=s[:m.start()]+new_hook+s[m.end():]
    for imp in ('import android.view.MotionEvent','import android.view.ViewConfiguration','import android.widget.PopupMenu'):
        if imp not in s:
            s=s.replace('import android.view.View\n',imp+'\nimport android.view.View\n',1)
    open(p,'w').write(s); print('[ok] MslHook patched (drag + hide)')

# 2) الملف الشخصي: الخط + بطاقة إعدادات القارئ
pt='app/src/main/java/eu/kanade/tachiyomi/ui/profile/ProfileTab.kt'
u=open(pt).read()
changed=False
if 'MslThemed' not in u:
    old='    @Composable\n    override fun Content() {'
    if old in u:
        u=u.replace(old,'    @Composable\n    override fun Content() {\n        eu.kanade.tachiyomi.mslime.MslThemed { ProfileContent() }\n    }\n\n    @Composable\n    private fun ProfileContent() {',1)
        changed=True; print('[ok] ProfileTab wrapped with Arabic fonts')
    else:
        print('[!!] ProfileTab Content() not found')
if 'MslDisplayFont' not in u:
    u=u.replace('Text(text = value, fontSize = 26.sp,','Text(text = value, fontFamily = eu.kanade.tachiyomi.mslime.MslDisplayFont, fontSize = 26.sp,',1)
    u=u.replace('fontSize = 24.sp,','fontSize = 24.sp,\n                    fontFamily = eu.kanade.tachiyomi.mslime.MslDisplayFont,',1)
    changed=True
if 'MslReaderSettingsCard' not in u:
    if 'eu.kanade.tachiyomi.mslime.MslAccountCard()' in u:
        u=u.replace('eu.kanade.tachiyomi.mslime.MslAccountCard()','eu.kanade.tachiyomi.mslime.MslAccountCard()\n            eu.kanade.tachiyomi.mslime.MslReaderSettingsCard()',1)
        changed=True; print('[ok] reader settings card added')
    else:
        print('[!!] MslAccountCard() call not found: run msl_google.sh first')
if changed: open(pt,'w').write(u)
# 3) بطاقة الرتبة
pr='app/src/main/java/eu/kanade/presentation/more/stats/RankSection.kt'
try:
    r=open(pr).read()
    if 'MslDisplayFont' not in r and 'fontSize = 22.sp,' in r:
        r=r.replace('fontSize = 22.sp,','fontSize = 22.sp,\n                    fontFamily = eu.kanade.tachiyomi.mslime.MslDisplayFont,',1)
        open(pr,'w').write(r); print('[ok] RankSection font')
except FileNotFoundError:
    print('[note] RankSection.kt not found')
EOF
if [ -z "$SKIP_GIT" ]; then
  git add -A && git commit -m "Nicer fonts in profile, draggable and hideable translate button" && git push && echo "[ok] pushed"
fi
