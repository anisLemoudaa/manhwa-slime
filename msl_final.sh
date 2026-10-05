bash msl_final.sh#!/bin/bash
# كل التعديلات الجديدة دفعة واحدة، ثم رفع واحد وبناء واحد (نسخة arm64 فقط)
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT" || exit 1

echo "== الدفعة 3 + 4: الشاشة المتحركة وترجمة الصفحة =="
(
cd "$ROOT" || exit 1
# الدفعة 3 (شاشة السلايم المتحركة) + الدفعة 4 (ترجمة الصفحة بالـ OCR)
K=app/src/main/java/eu/kanade/tachiyomi/mslime
M=app/src/main/AndroidManifest.xml
G=app/build.gradle.kts
for f in $M $G; do [ -f "$f" ] || { echo "[!!] $f not found"; exit 1; }; done
[ -f app/src/main/res/drawable-nodpi/ic_mihon.png ] || echo "[!!] slime logo missing: run 'bash msl_logo.sh' first"
mkdir -p $K

# ---------- الدفعة 3: شاشة البداية المتحركة ----------
cat > $K/SlimeIntroActivity.kt <<'EOF'
package eu.kanade.tachiyomi.mslime

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SlimeIntroActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SlimeIntro {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }
}

@Composable
private fun SlimeIntro(onDone: () -> Unit) {
    val drop = remember { Animatable(-600f) }
    val sx = remember { Animatable(1f) }
    val sy = remember { Animatable(1f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        drop.animateTo(0f, tween(450, easing = FastOutSlowInEasing))
        coroutineScope {
            launch {
                sx.animateTo(1.4f, tween(110))
                sx.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            }
            launch {
                sy.animateTo(0.6f, tween(110))
                sy.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            }
            launch { fade.animateTo(1f, tween(500)) }
        }
        delay(300)
        onDone()
    }
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F13)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.ic_mihon),
                contentDescription = null,
                modifier = Modifier.size(160.dp).graphicsLayer {
                    translationY = drop.value
                    scaleX = sx.value
                    scaleY = sy.value
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Manhwa Slime",
                color = Color(0xFF2DB8FD),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.graphicsLayer { alpha = fade.value },
            )
        }
    }
}
EOF
cat > app/src/main/res/values/msl_intro.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.MslIntro" parent="@android:style/Theme.DeviceDefault.NoActionBar">
        <item name="android:windowBackground">#0F0F13</item>
        <item name="android:statusBarColor">#0F0F13</item>
        <item name="android:navigationBarColor">#0F0F13</item>
    </style>
</resources>
EOF

# ---------- الدفعة 4: ترجمة الصفحة (OCR + ترجمة) ----------
cat > $K/MslTranslate.kt <<'EOF'
package eu.kanade.tachiyomi.mslime

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** يُشغَّل تلقائياً عند بدء التطبيق ليضيف زر الترجمة في القارئ. */
class MslInit : ContentProvider() {
    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.registerActivityLifecycleCallbacks(MslHook)
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

object MslHook : Application.ActivityLifecycleCallbacks {
    private const val TAG = "msl_fab"

    override fun onActivityResumed(activity: Activity) {
        if (!activity.javaClass.name.endsWith("ReaderActivity")) return
        if (activity.window.decorView.findViewWithTag<View>(TAG) != null) return
        val d = activity.resources.displayMetrics.density
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
        val size = (48 * d).toInt()
        val lp = FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.CENTER_VERTICAL)
        lp.marginEnd = (8 * d).toInt()
        activity.addContentView(fab, lp)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

object MslTranslate {
    private fun toast(a: Activity, m: String) = Toast.makeText(a, m, Toast.LENGTH_SHORT).show()

    fun run(activity: Activity, fab: View) {
        val v = activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        fab.visibility = View.INVISIBLE
        toast(activity, "جارٍ قراءة الصفحة...")
        v.postDelayed({
            PixelCopy.request(activity.window, bmp, { code ->
                fab.visibility = View.VISIBLE
                if (code != PixelCopy.SUCCESS) {
                    toast(activity, "تعذّر التقاط الشاشة")
                } else {
                    process(activity, bmp)
                }
            }, Handler(Looper.getMainLooper()))
        }, 150)
    }

    private fun process(activity: Activity, bmp: Bitmap) {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { res ->
                val items = res.textBlocks
                    .filter { it.boundingBox != null && it.text.isNotBlank() }
                    .map { it.boundingBox!! to it.text.replace("\n", " ") }
                if (items.isEmpty()) {
                    toast(activity, "لم أجد نصاً إنجليزياً في الصفحة")
                } else {
                    translate(activity, bmp, items)
                }
            }
            .addOnFailureListener { toast(activity, "فشلت قراءة النص: ${it.message}") }
    }

    private fun translate(activity: Activity, bmp: Bitmap, items: List<Pair<Rect, String>>) {
        toast(activity, "جارٍ الترجمة (أول مرة يُحمَّل نموذج الترجمة)...")
        val tr = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.ARABIC)
                .build(),
        )
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                val out = mutableListOf<Pair<Rect, String>>()
                fun next(i: Int) {
                    if (i >= items.size) {
                        tr.close()
                        show(activity, bmp, out)
                        return
                    }
                    tr.translate(items[i].second)
                        .addOnSuccessListener { t -> out.add(items[i].first to t); next(i + 1) }
                        .addOnFailureListener { next(i + 1) }
                }
                next(0)
            }
            .addOnFailureListener { toast(activity, "تعذّر تحميل نموذج الترجمة: ${it.message}") }
    }

    private fun layoutFor(text: String, tp: TextPaint, size: Float, width: Int): StaticLayout {
        tp.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, tp, width.coerceAtLeast(10))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
    }

    private fun show(activity: Activity, src: Bitmap, items: List<Pair<Rect, String>>) {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bmp)
        val bg = Paint()
        bg.color = Color.WHITE
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.color = Color.BLACK
        tp.isFakeBoldText = true
        for ((box, text) in items) {
            val r = Rect(box)
            r.inset(-6, -6)
            c.drawRect(r, bg)
            var size = 40f
            var layout = layoutFor(text, tp, size, r.width())
            while (layout.height > r.height() && size > 12f) {
                size -= 2f
                layout = layoutFor(text, tp, size, r.width())
            }
            c.save()
            c.translate(r.left.toFloat(), r.top + (r.height() - layout.height).coerceAtLeast(0) / 2f)
            layout.draw(c)
            c.restore()
        }
        val iv = ImageView(activity)
        iv.setImageBitmap(bmp)
        iv.setBackgroundColor(Color.BLACK)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(iv)
        iv.setOnClickListener { dialog.dismiss() }
        dialog.show()
        toast(activity, "اضغط على الصورة للعودة")
    }
}
EOF

# ---------- تعديل الـ Manifest و build.gradle ----------
python3 - <<'EOF'
import re
p='app/src/main/AndroidManifest.xml'
s=open(p).read()
if 'SlimeIntroActivity' in s:
    print('[ok] manifest already patched')
else:
    m=re.search(r'<activity[^>]*MainActivity[\s\S]*?</activity>',s)
    if not m: print('[!!] MainActivity block not found'); raise SystemExit(1)
    blk=m.group(0)
    new=re.sub(r'\s*<category android:name="android.intent.category.LAUNCHER"\s*/>','',blk,count=1)
    if new==blk: print('[!!] LAUNCHER category not found in MainActivity'); raise SystemExit(1)
    s=s.replace(blk,new)
    add='''
        <activity
            android:name=".mslime.SlimeIntroActivity"
            android:exported="true"
            android:noHistory="true"
            android:theme="@style/Theme.MslIntro">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <provider
            android:name=".mslime.MslInit"
            android:authorities="${applicationId}.mslinit"
            android:exported="false" />
'''
    if '</application>' not in s: print('[!!] </application> not found'); raise SystemExit(1)
    s=s.replace('</application>',add+'    </application>',1)
    open(p,'w').write(s); print('[ok] manifest patched')
g='app/build.gradle.kts'
t=open(g).read()
if 'mlkit' in t:
    print('[ok] gradle already patched')
else:
    mm=re.search(r'\ndependencies \{\n',t)
    if not mm: print('[!!] dependencies block not found'); raise SystemExit(1)
    t=t[:mm.end()]+'    implementation("com.google.mlkit:text-recognition:16.0.1")\n    implementation("com.google.mlkit:translate:17.0.3")\n'+t[mm.end():]
    open(g,'w').write(t); print('[ok] gradle patched')
EOF

) || echo "[!!] batch 3+4 reported a problem (see above)"

echo "== الدفعة 2: بطاقة المشاركة =="
(
cd "$ROOT" || exit 1
# الدفعة 2: بطاقة مشاركة الرتبة (صورة بتصميم السلايم)
K=app/src/main/java/eu/kanade/tachiyomi/mslime
D=app/src/main/java/eu/kanade/presentation/more/stats
M=app/src/main/AndroidManifest.xml
[ -f "$D/RankSection.kt" ] || { echo "[!!] RankSection.kt not found: run msl_rank.sh first"; exit 1; }
[ -f "$M" ] || { echo "[!!] manifest not found"; exit 1; }
mkdir -p $K app/src/main/res/xml

cat > $K/MslShare.kt <<'EOF'
package eu.kanade.tachiyomi.mslime

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.content.FileProvider
import eu.kanade.tachiyomi.R
import java.io.File
import java.io.FileOutputStream

class MslFileProvider : FileProvider()

object MslShare {
    private fun line(c: Canvas, text: String, y: Float, size: Float, color: Int, bold: Boolean = false) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color
        p.textSize = size
        p.isFakeBoldText = bold
        p.textAlign = Paint.Align.CENTER
        c.drawText(text, c.width / 2f, y, p)
    }

    private fun draw(ctx: Context, rank: String, title: String, read: Int, total: Int, downloaded: Int, nextLine: String): Bitmap {
        val w = 1080
        val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint()
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF0F0F13.toInt(), 0xFF123A5A.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val logo = BitmapFactory.decodeResource(ctx.resources, R.drawable.ic_mihon)
        if (logo != null) {
            val s = Bitmap.createScaledBitmap(logo, 260, 260, true)
            c.drawBitmap(s, (w - 260) / 2f, 110f, null)
        }
        line(c, "Manhwa Slime", 470f, 56f, 0xFF2DB8FD.toInt(), true)
        line(c, rank, 720f, 260f, Color.WHITE, true)
        line(c, title, 830f, 68f, 0xFF2DB8FD.toInt(), true)
        line(c, "الفصول المقروءة: $read", 960f, 54f, Color.WHITE, true)
        line(c, "فصول مكتبتي: $total", 1040f, 44f, 0xFFB8C4CE.toInt())
        line(c, "الفصول المحمّلة: $downloaded", 1105f, 44f, 0xFFB8C4CE.toInt())
        line(c, nextLine, 1210f, 42f, 0xFF2DB8FD.toInt())
        line(c, "Manhwa Slime", 1300f, 34f, 0xFF6E7B86.toInt())
        return bmp
    }

    fun shareRank(ctx: Context, rank: String, title: String, read: Int, total: Int, downloaded: Int, nextLine: String) {
        val bmp = draw(ctx, rank, title, read, total, downloaded, nextLine)
        val dir = File(ctx.cacheDir, "msl")
        dir.mkdirs()
        val f = File(dir, "manhwa_slime_rank.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".mslshare", f)
        val i = Intent(Intent.ACTION_SEND)
        i.type = "image/png"
        i.putExtra(Intent.EXTRA_STREAM, uri)
        i.putExtra(Intent.EXTRA_TEXT, "رتبتي في Manhwa Slime: $rank")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(i, "مشاركة بطاقة الرتبة")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(chooser)
    }
}
EOF

cat > app/src/main/res/xml/msl_share_paths.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="msl" path="msl/" />
</paths>
EOF

# إعادة كتابة بطاقة الرتبة مع زر المشاركة
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.more.stats.data.StatsData
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.mslime.MslShare

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
    val ctx = LocalContext.current
    val read = chapters.readChapterCount
    val index = readerRanks.indexOfLast { read >= it.min }.coerceAtLeast(0)
    val rank = readerRanks[index]
    val next = readerRanks.getOrNull(index + 1)
    val nextLine = if (next != null) "باقي ${next.min - read} فصل للوصول إلى الرتبة ${next.name}" else "وصلت إلى أعلى رتبة 👑"
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
        }
        Text(text = nextLine, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = {
                MslShare.shareRank(
                    ctx,
                    rank.name,
                    rank.title,
                    read,
                    chapters.totalChapterCount,
                    chapters.downloadCount,
                    nextLine,
                )
            },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Text("مشاركة بطاقتي")
        }
    }
}
EOF

python3 - <<'EOF'
p='app/src/main/AndroidManifest.xml'
s=open(p).read()
if 'MslFileProvider' in s:
    print('[ok] manifest already has share provider')
else:
    add='''        <provider
            android:name=".mslime.MslFileProvider"
            android:authorities="${applicationId}.mslshare"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/msl_share_paths" />
        </provider>
'''
    if '</application>' not in s: print('[!!] </application> not found'); raise SystemExit(1)
    s=s.replace('</application>',add+'    </application>',1)
    open(p,'w').write(s); print('[ok] manifest patched (share provider)')
EOF

) || echo "[!!] batch 2 reported a problem (see above)"

echo "== ملف البناء: نسخة arm64 فقط =="
W=.github/workflows/msp.yml
if [ -f "$W" ] && ! grep -q 'app-arm64-v8a-debug.apk' "$W"; then
  sed -i 's|path: app/build/outputs/apk/debug/\*.apk|path: app/build/outputs/apk/debug/app-arm64-v8a-debug.apk|' "$W"
fi
grep -n 'arm64' "$W" || echo "[!!] arm64 line not found in $W"

echo "== فحص نهائي =="
echo "intro in manifest: $(grep -c SlimeIntroActivity app/src/main/AndroidManifest.xml)"
echo "share provider:   $(grep -c MslFileProvider app/src/main/AndroidManifest.xml)"
echo "mlkit deps:       $(grep -c mlkit app/build.gradle.kts)"
ls app/src/main/java/eu/kanade/tachiyomi/mslime

if [ -z "$SKIP_GIT" ]; then
  git add -A && git commit -m "All new features: intro, translate, share card" && git push && echo "[ok] pushed"
fi
