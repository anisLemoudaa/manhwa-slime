package eu.kanade.tachiyomi.mslime

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** يُشغَّل تلقائياً عند بدء التطبيق ليضيف زر الترجمة في القارئ. */
class MslInit : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application
        app?.getSharedPreferences("msl", Context.MODE_PRIVATE)
            ?.edit()
            ?.remove("ai_key")
            ?.apply()
        app?.registerActivityLifecycleCallbacks(MslHook)
        return true
    }
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

object MslHook : Application.ActivityLifecycleCallbacks {
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
        pm.menu.add(0, 2, 1, "إعادة الزر لمكانه")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> {
                    setEnabled(activity, false)
                    fab.visibility = View.GONE
                }
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
    override fun onActivityStopped(activity: Activity) {
        if (activity.javaClass.name.endsWith("MainActivity")) {
            MslCloudSync.syncNow(activity)
        }
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

object MslFont {
    private var cached: Typeface? = null
    fun get(ctx: Context): Typeface {
        cached?.let { return it }
        val t = try {
            Typeface.createFromAsset(ctx.assets, "fonts/msl_bubble.ttf")
        } catch (e: Exception) {
            Typeface.DEFAULT_BOLD
        }
        cached = t
        return t
    }
}

object MslTranslate {
    private fun toast(a: Activity, m: String) = Toast.makeText(a, m, Toast.LENGTH_SHORT).show()

    private fun captureTarget(activity: Activity, preferred: View? = null): View {
        val viewerId = activity.resources.getIdentifier("viewer_container", "id", activity.packageName)
        val viewer = if (viewerId != 0) activity.findViewById<View>(viewerId) else null
        return (preferred ?: viewer)?.takeIf { it.width > 0 && it.height > 0 } ?: activity.window.decorView
    }

    private fun requestCapture(
        activity: Activity,
        target: View,
        destination: Bitmap,
        callback: (Int) -> Unit,
    ) {
        val location = IntArray(2)
        target.getLocationInWindow(location)
        val sourceRect = Rect(location[0], location[1], location[0] + target.width, location[1] + target.height)
        PixelCopy.request(
            activity.window,
            sourceRect,
            destination,
            PixelCopy.OnPixelCopyFinishedListener { result -> callback(result) },
            Handler(Looper.getMainLooper()),
        )
    }

    fun run(activity: Activity, fab: View) {
        if (!fab.isEnabled) return
        val target = captureTarget(activity)
        if (target.width <= 0 || target.height <= 0) {
            toast(activity, "لا يمكن التقاط الصفحة الآن؛ حاول بعد اكتمال القارئ")
            return
        }
        val bmp = runCatching { Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888) }
            .getOrElse {
                toast(activity, "تعذّر تجهيز صورة الصفحة")
                return
            }
        val restoreFab = {
            fab.isEnabled = true
            fab.alpha = 0.7f
            fab.visibility = View.VISIBLE
        }
        fab.isEnabled = false
        fab.alpha = 0.35f
        fab.visibility = View.INVISIBLE
        toast(activity, "جارٍ قراءة الصفحة...")
        target.postDelayed({
            runCatching {
                requestCapture(activity, target, bmp) { code ->
                    if (code != PixelCopy.SUCCESS) {
                        restoreFab()
                        toast(activity, "تعذّر التقاط الصفحة")
                    } else {
                        fab.visibility = View.VISIBLE
                        process(activity, bmp, restoreFab)
                    }
                }
            }.onFailure {
                restoreFab()
                toast(activity, "تعذّر التقاط الصفحة")
            }
        }, 150)
    }

    fun run(activity: Activity) = runForView(activity, null)

    fun runForView(activity: Activity, pageView: View?) {
        val target = captureTarget(activity, pageView)
        if (target.width <= 0 || target.height <= 0) {
            toast(activity, "لا يمكن التقاط الصفحة الآن؛ حاول بعد اكتمال القارئ")
            return
        }
        val bmp = runCatching { Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888) }
            .getOrElse {
                toast(activity, "تعذّر تجهيز صورة الصفحة")
                return
            }
        toast(activity, "جارٍ قراءة الصفحة...")
        target.postDelayed({
            runCatching {
                requestCapture(activity, target, bmp) { code ->
                    if (code != PixelCopy.SUCCESS) toast(activity, "تعذّر التقاط الصفحة")
                    else process(activity, bmp)
                }
            }.onFailure { toast(activity, "تعذّر التقاط الصفحة") }
        }, 150)
    }

    private fun process(activity: Activity, bmp: Bitmap, onComplete: () -> Unit = {}) {
        val finish = { activity.runOnUiThread { onComplete() } }
        val image = runCatching { InputImage.fromBitmap(bmp, 0) }.getOrElse {
            showOcrUnavailable(activity, it)
            finish()
            return
        }

        // The default recognizer only reads Latin script. Manga/manhwa commonly
        // contains Japanese, Korean, or Chinese text, so run the bundled models
        // and select the result with the strongest script-aware score.
        val clients = listOfNotNull(
            runCatching { "ja" to TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }.getOrNull(),
            runCatching { "ko" to TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }.getOrNull(),
            runCatching { "zh" to TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }.getOrNull(),
            runCatching { "latin" to TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }.getOrNull(),
        )
        val tasks = clients.mapNotNull { (script, recognizer) ->
            runCatching { script to recognizer.process(image) }
                .onFailure { closeRecognizer(recognizer) }
                .getOrNull()
        }
        if (tasks.isEmpty()) {
            clients.forEach { closeRecognizer(it.second) }
            showOcrUnavailable(activity, IllegalStateException("No ML Kit text recognizer could be started"))
            finish()
            return
        }

        Tasks.whenAllComplete(tasks.map { it.second }).addOnCompleteListener {
            clients.forEach { closeRecognizer(it.second) }
            if (activity.isFinishing || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) {
                finish()
                return@addOnCompleteListener
            }
            val candidates = tasks.mapNotNull { (script, task) ->
                if (!task.isSuccessful) return@mapNotNull null
                val items = task.result.textBlocks
                    .filter { it.boundingBox != null && it.text.isNotBlank() }
                    .map { it.boundingBox!! to normalizeOcr(it.text) }
                    .filter { it.second.isNotBlank() }
                    .sortedWith(compareBy({ it.first.top }, { it.first.left }))
                if (items.isEmpty()) return@mapNotNull null
                val text = items.joinToString(" ") { it.second }
                val scriptChars = when (script) {
                    "ja" -> text.count { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' }
                    "ko" -> text.count { it in '\uac00'..'\ud7af' }
                    "zh" -> text.count { it in '\u4e00'..'\u9fff' }
                    else -> text.count(Char::isLetter)
                }
                Triple(script, items, text.length + scriptChars * 5)
            }
            val best = candidates.maxByOrNull { it.third }
            if (best == null) {
                val failure = tasks.firstOrNull { !it.second.isSuccessful }?.second?.exception
                if (failure != null) showOcrUnavailable(activity, failure)
                else toast(activity, "لم أجد نصًا واضحًا في الصفحة. جرّب تكبيرها أو تحسين وضوحها.")
                finish()
            } else {
                android.util.Log.d("MslTranslate", "OCR selected ${best.first} recognizer with ${best.second.size} text blocks")
                translateItems(activity, bmp, best.second, finish)
            }
        }.addOnFailureListener { error ->
            clients.forEach { closeRecognizer(it.second) }
            showOcrUnavailable(activity, error)
            finish()
        }
    }

    private fun closeRecognizer(recognizer: TextRecognizer) {
        runCatching { recognizer.close() }
    }

    private fun showOcrUnavailable(activity: Activity, error: Throwable) {
        android.util.Log.w("MslTranslate", "ML Kit text recognition failed", error)
        if (activity.isFinishing || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) return
        toast(activity, "تعذّر التعرّف على نص الصفحة. جرّب تكبيرها أو صفحة أوضح؛ لا يتطلب OCR الحالي تحديث خدمات Google Play.")
    }

    private fun translateItems(
        activity: Activity,
        bmp: Bitmap,
        items: List<Pair<Rect, String>>,
        onComplete: () -> Unit,
    ) {
        mlKit(activity, bmp, items, onComplete)
    }

    private fun mlKit(
        activity: Activity,
        bmp: Bitmap,
        items: List<Pair<Rect, String>>,
        onComplete: () -> Unit,
    ) {
        val sourceLanguage = detectSourceLanguage(items.joinToString(" ") { it.second })
        val tr = runCatching {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLanguage)
                    .setTargetLanguage(TranslateLanguage.ARABIC)
                    .build(),
            )
        }.getOrElse {
            showTranslationUnavailable(activity, it)
            onComplete()
            return
        }
        val modelTask = runCatching {
            tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
        }.getOrElse {
            closeTranslator(tr)
            showTranslationUnavailable(activity, it)
            onComplete()
            return
        }
        modelTask
            .addOnSuccessListener {
                if (activity.isFinishing || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) {
                    closeTranslator(tr)
                    onComplete()
                } else {
                    // Translate the whole page as one scene so names, pronouns and tone share context.
                    val marker = "\n\nMSL_TRANSLATION_BREAK_7F3A\n\n"
                    val scene = items.joinToString(marker) { it.second }
                    runCatching { tr.translate(scene) }
                        .onSuccess { translationTask ->
                            translationTask
                                .addOnSuccessListener { translated ->
                                    val chunks = translated.split("MSL_TRANSLATION_BREAK_7F3A")
                                        .map { it.trim() }
                                    if (chunks.size == items.size) {
                                        closeTranslator(tr)
                                        show(activity, bmp, items.mapIndexed { i, item -> item.first to chunks[i] })
                                        onComplete()
                                    } else {
                                        translateIndividually(activity, bmp, tr, items, onComplete)
                                    }
                                }
                                .addOnFailureListener { translateIndividually(activity, bmp, tr, items, onComplete) }
                        }
                        .onFailure {
                            translateIndividually(activity, bmp, tr, items, onComplete)
                        }
                }
            }
            .addOnFailureListener {
                closeTranslator(tr)
                showTranslationUnavailable(activity, it)
                onComplete()
            }
    }

    private fun closeTranslator(tr: com.google.mlkit.nl.translate.Translator) {
        runCatching { tr.close() }
    }

    private fun showTranslationUnavailable(activity: Activity, error: Throwable) {
        android.util.Log.w("MslTranslate", "ML Kit translation unavailable", error)
        if (activity.isFinishing || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) return
        toast(
            activity,
            "تعذّرت الترجمة الآلية. تحقق من اتصال الإنترنت لتنزيل نموذج اللغة ثم أعد المحاولة.",
        )
    }

    private fun translateIndividually(
        activity: Activity,
        bmp: Bitmap,
        tr: com.google.mlkit.nl.translate.Translator,
        items: List<Pair<Rect, String>>,
        onComplete: () -> Unit,
    ) {
        val out = mutableListOf<Pair<Rect, String>>()
        fun next(i: Int) {
            if (i >= items.size) {
                closeTranslator(tr)
                if (out.isEmpty()) {
                    showTranslationUnavailable(activity, IllegalStateException("No text could be translated"))
                } else {
                    show(activity, bmp, out)
                }
                onComplete()
                return
            }
            runCatching { tr.translate(items[i].second) }
                .onSuccess { translationTask ->
                    translationTask
                        .addOnSuccessListener { translated ->
                            out.add(items[i].first to translated)
                            next(i + 1)
                        }
                        .addOnFailureListener { next(i + 1) }
                }
                .onFailure { next(i + 1) }
        }
        next(0)
    }

    private fun normalizeOcr(value: String): String {
        return value
            .replace('“', '"')
            .replace('”', '"')
            .replace('’', '\'')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun detectSourceLanguage(text: String): String = when {
        text.any { it in '\u3040'..'\u30ff' } -> TranslateLanguage.JAPANESE
        text.any { it in '\uac00'..'\ud7af' } -> TranslateLanguage.KOREAN
        text.any { it in '\u4e00'..'\u9fff' } -> TranslateLanguage.CHINESE
        else -> TranslateLanguage.ENGLISH
    }

    private fun layoutFor(text: String, tp: TextPaint, size: Float, width: Int): StaticLayout {
        tp.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, tp, width.coerceAtLeast(10))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(android.text.TextDirectionHeuristics.RTL)
            .setIncludePad(false)
            .build()
    }

    private fun show(activity: Activity, src: Bitmap, items: List<Pair<Rect, String>>) {
        if (activity.isFinishing || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) {
            return
        }
        val bmp = runCatching { src.copy(Bitmap.Config.ARGB_8888, true) }.getOrElse {
            toast(activity, "تعذّر تجهيز نتيجة الترجمة")
            return
        }
        val c = Canvas(bmp)
        val bg = Paint()
        bg.color = Color.WHITE
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.color = 0xFF111111.toInt()
        tp.typeface = MslFont.get(activity)
        for ((box, text) in items) {
            if (text.isBlank()) continue
            val r = Rect(box)
            val inset = (box.width() / 24).coerceIn(3, 8)
            r.inset(-inset, -inset)
            r.intersect(0, 0, bmp.width, bmp.height)
            if (r.width() < 12 || r.height() < 12) continue
            c.drawRoundRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), 18f, 18f, bg)
            var size = minOf(44f, r.height() * 0.72f)
            var layout = layoutFor(text, tp, size, r.width() - 8)
            while ((layout.height > r.height() - 4 || layout.lineCount > 4) && size > 8f) {
                size -= 1f
                layout = layoutFor(text, tp, size, r.width() - 8)
            }
            c.save()
            c.clipRect(r.left + 2, r.top + 2, r.right - 2, r.bottom - 2)
            c.translate(r.left + 4f, r.top + (r.height() - layout.height).coerceAtLeast(0) / 2f)
            layout.draw(c)
            c.restore()
        }
        val iv = ImageView(activity)
        iv.setImageBitmap(bmp)
        iv.setBackgroundColor(Color.BLACK)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        runCatching {
            val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.setContentView(iv)
            iv.setOnClickListener { dialog.dismiss() }
            dialog.show()
            toast(activity, "اضغط على الصورة للعودة")
        }.onFailure { toast(activity, "تعذّر عرض نتيجة الترجمة") }
    }
}
