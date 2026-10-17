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
import eu.kanade.tachiyomi.util.system.activeNetworkState
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
    private const val CACHE_PREFERENCES = "msl_translation_cache"
    private const val GLOSSARY_ASSET = "msl_translation_glossary.json"
    private const val CACHE_LIMIT = 128

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

    /** PixelCopy is unavailable on older Android versions and may reject some reader surfaces. */
    private fun captureWithFallback(activity: Activity, target: View, destination: Bitmap, done: (Boolean) -> Unit) {
        fun drawView(): Boolean = runCatching {
            destination.eraseColor(Color.TRANSPARENT)
            target.draw(Canvas(destination))
            true
        }.onFailure { android.util.Log.w("MslTranslate", "Reader view draw fallback failed", it) }
            .getOrDefault(false)

        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            done(drawView())
            return
        }
        runCatching {
            requestCapture(activity, target, destination) { code ->
                if (code == PixelCopy.SUCCESS) {
                    done(true)
                } else {
                    android.util.Log.w("MslTranslate", "PixelCopy failed with code $code; trying view.draw")
                    done(drawView())
                }
            }
        }.onFailure {
            android.util.Log.w("MslTranslate", "PixelCopy request failed; trying view.draw", it)
            done(drawView())
        }
    }

    fun run(activity: Activity, fab: View) {
        if (!fab.isEnabled) return
        val target = captureTarget(activity)
        android.util.Log.i("MslTranslate", "Translate tapped; target=${target.javaClass.simpleName}, size=${target.width}x${target.height}")
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
            captureWithFallback(activity, target, bmp) { captured ->
                if (!captured) {
                    android.util.Log.e("MslTranslate", "Could not capture reader content using PixelCopy or View.draw")
                    restoreFab()
                    toast(activity, "تعذّر التقاط الصفحة؛ أعد المحاولة بعد اكتمال تحميلها")
                } else {
                    fab.visibility = View.VISIBLE
                    process(activity, bmp, restoreFab)
                }
            }
        }, 150)
    }

    fun run(activity: Activity) = runForView(activity, null)

    fun runForView(activity: Activity, pageView: View?) {
        val target = captureTarget(activity, pageView)
        android.util.Log.i("MslTranslate", "Translate page view; target=${target.javaClass.simpleName}, size=${target.width}x${target.height}")
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
            captureWithFallback(activity, target, bmp) { captured ->
                if (captured) process(activity, bmp)
                else {
                    android.util.Log.e("MslTranslate", "Could not capture page view using PixelCopy or View.draw")
                    toast(activity, "تعذّر التقاط الصفحة؛ أعد المحاولة بعد اكتمال تحميلها")
                }
            }
        }, 150)
    }

    private fun process(activity: Activity, bmp: Bitmap, onComplete: () -> Unit = {}) {
        android.util.Log.i("MslTranslate", "Starting OCR for ${bmp.width}x${bmp.height} image")
        val finish = { activity.runOnUiThread { onComplete() } }
        val image = runCatching { InputImage.fromBitmap(bmp, 0) }.getOrElse {
            showOcrUnavailable(activity, it)
            finish()
            return
        }

        // Keep all page OCR options. Translation itself is deliberately English -> Arabic only.
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
            data class Candidate(val script: String, val items: List<Pair<Rect, String>>, val text: String)
            val candidates = tasks.mapNotNull { (script, task) ->
                if (!task.isSuccessful) return@mapNotNull null
                val items = task.result.textBlocks
                    .filter { it.boundingBox != null && it.text.isNotBlank() }
                    .map { it.boundingBox!! to MslTranslationText.normalizeOcr(it.text) }
                    .filter { it.second.isNotBlank() }
                    .sortedWith(compareBy({ it.first.top }, { it.first.left }))
                if (items.isEmpty()) return@mapNotNull null
                val text = items.joinToString(" ") { it.second }
                Candidate(script, items, text)
            }
            // Do not allow a non-English recognizer result to become input to the translator.
            val best = candidates.firstOrNull { it.script == "latin" && MslTranslationText.isEnglishCandidate(it.text) }
            if (best == null) {
                val failure = tasks.firstOrNull { !it.second.isSuccessful }?.second?.exception
                if (failure != null) showOcrUnavailable(activity, failure)
                else if (candidates.isNotEmpty()) toast(activity, "الترجمة المتاحة هنا English → Arabic فقط؛ لم يُرسل النص غير الإنجليزي.")
                else toast(activity, "لم أجد نصًا واضحًا في الصفحة. جرّب تكبيرها أو تحسين وضوحها.")
                finish()
            } else {
                android.util.Log.d("MslTranslate", "OCR selected ${best.script} recognizer with ${best.items.size} text blocks")
                translateItems(activity, bmp, best.items, finish)
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

    private class PreferencesTranslationCacheStore(context: Context) : MslTranslationCacheStore {
        private val preferences = context.getSharedPreferences(CACHE_PREFERENCES, Context.MODE_PRIVATE)

        override fun get(key: String): String? = preferences.getString(key, null)
        override fun put(key: String, value: String) { preferences.edit().putString(key, value).apply() }
        override fun remove(key: String) { preferences.edit().remove(key).apply() }
        override fun keys(): Set<String> = preferences.all.keys
    }

    private fun cache(context: Context): MslBoundedTranslationCache = MslBoundedTranslationCache(
        PreferencesTranslationCacheStore(context.applicationContext),
        CACHE_LIMIT,
    )

    private fun glossary(context: Context): MslGlossary = runCatching {
        context.assets.open(GLOSSARY_ASSET).bufferedReader().use { MslGlossary.fromJson(it.readText()) }
    }.onFailure {
        android.util.Log.w("MslTranslate", "Glossary unavailable; using empty glossary", it)
    }.getOrDefault(MslGlossary.EMPTY)

    private class MlKitTranslationProvider(
        private val translator: com.google.mlkit.nl.translate.Translator,
    ) : MslTranslationProvider {
        override fun translate(source: String, onSuccess: (String) -> Unit, onFailure: (Throwable) -> Unit) {
            runCatching { translator.translate(source) }
                .onFailure(onFailure)
                .onSuccess { task ->
                    task
                        .addOnSuccessListener { value -> onSuccess(value) }
                        .addOnFailureListener { error -> onFailure(error) }
                }
        }
    }

    private fun translateItems(
        activity: Activity,
        bmp: Bitmap,
        items: List<Pair<Rect, String>>,
        onComplete: () -> Unit,
    ) {
        val processor = MslTranslationProcessor(cache(activity), glossary(activity))
        val translatedRegions = mutableListOf<Pair<Rect, String>>()
        var failedRegions = 0
        val uncachedItems = mutableListOf<Pair<Rect, String>>()
        items.forEach { item ->
            val chunks = processor.plan(item.second)
            if (chunks.isEmpty()) {
                failedRegions++
            } else {
                val cachedResponses = chunks.mapNotNull { processor.cached(it.source) }
                if (cachedResponses.size == chunks.size) {
                    val translated = processor.join(chunks, cachedResponses)
                    if (translated != null && MslTranslationText.isUsableTranslation(translated)) {
                        translatedRegions += item.first to translated
                    } else {
                        failedRegions++
                    }
                } else {
                    uncachedItems += item
                }
            }
        }
        if (uncachedItems.isEmpty()) {
            if (translatedRegions.isNotEmpty()) show(activity, bmp, translatedRegions)
            if (failedRegions > 0) toast(activity, "تعذّرت ترجمة $failedRegions منطقة؛ لم تُخفَ الأخطاء.")
            if (translatedRegions.isEmpty() && failedRegions == 0) toast(activity, "لم أجد نصًا صالحًا للترجمة.")
            onComplete()
            return
        }
        val isOnline = runCatching { activity.activeNetworkState().isOnline }.getOrDefault(false)
        if (!isOnline) {
            if (translatedRegions.isNotEmpty()) show(activity, bmp, translatedRegions)
            val message = if (translatedRegions.isNotEmpty()) {
                "عُرضت الترجمات المحفوظة؛ المناطق الأخرى تحتاج اتصالًا بالإنترنت للترجمة."
            } else {
                "لا يوجد اتصال بالإنترنت ولا توجد ترجمة محفوظة لهذه الصفحة."
            }
            toast(activity, message)
            onComplete()
            return
        }
        val terminal = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val activeTranslator = AtomicReference<com.google.mlkit.nl.translate.Translator?>(null)
        val timeout = Runnable {
            if (terminal.compareAndSet(false, true)) {
                activeTranslator.get()?.let(::closeTranslator)
                if (translatedRegions.isNotEmpty()) show(activity, bmp, translatedRegions)
                toast(activity, "انتهت مهلة الترجمة للمناطق غير المخزنة؛ يمكنك المحاولة لاحقًا.")
                onComplete()
            }
        }
        handler.postDelayed(timeout, 90_000L)
        fun finishOnce() {
            if (terminal.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                onComplete()
            }
        }
        val translator = runCatching {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(TranslateLanguage.ARABIC)
                    .build(),
            )
        }.getOrElse {
            terminal.set(true)
            handler.removeCallbacks(timeout)
            if (translatedRegions.isNotEmpty()) show(activity, bmp, translatedRegions)
            showTranslationUnavailable(activity, it)
            if (failedRegions > 0) toast(activity, "تعذّرت ترجمة $failedRegions منطقة غير مخزنة.")
            onComplete()
            return
        }
        activeTranslator.set(translator)
        val provider = MlKitTranslationProvider(translator)

        toast(activity, "جارٍ تجهيز نموذج الترجمة English → Arabic؛ قد يحتاج تنزيلًا أول مرة...")
        val modelTask = runCatching {
            // No API key or cloud provider: ML Kit downloads its on-device model on demand.
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
        }.getOrElse {
            terminal.set(true)
            handler.removeCallbacks(timeout)
            closeTranslator(translator)
            if (translatedRegions.isNotEmpty()) show(activity, bmp, translatedRegions)
            showTranslationModelUnavailable(activity, it)
            if (failedRegions > 0) toast(activity, "تعذّرت ترجمة $failedRegions منطقة غير مخزنة.")
            onComplete()
            return
        }
        modelTask
            .addOnSuccessListener {
                if (terminal.get()) {
                    closeTranslator(translator)
                    return@addOnSuccessListener
                }
                if (activity.isFinishing || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) {
                    closeTranslator(translator)
                    finishOnce()
                } else {
                    translateRegions(activity, bmp, uncachedItems, processor, provider, translatedRegions, { terminal.get() }) { failed ->
                        if (terminal.get()) return@translateRegions
                        failedRegions += failed
                        closeTranslator(translator)
                        if (translatedRegions.isNotEmpty()) {
                            show(activity, bmp, translatedRegions)
                        }
                        if (failedRegions > 0) {
                            val suffix = if (translatedRegions.isEmpty()) {
                                "لم تنجح ترجمة أي منطقة."
                            } else {
                                "نجحت ترجمة ${translatedRegions.size} منطقة، وتعذّرت ترجمة $failedRegions منطقة؛ لم تُخفَ الأخطاء."
                            }
                            toast(activity, suffix)
                        }
                        finishOnce()
                    }
                }
            }
            .addOnFailureListener {
                if (terminal.get()) return@addOnFailureListener
                closeTranslator(translator)
                if (translatedRegions.isNotEmpty()) show(activity, bmp, translatedRegions)
                showTranslationModelUnavailable(activity, it)
                finishOnce()
            }
    }

    private fun translateRegions(
        activity: Activity,
        bmp: Bitmap,
        items: List<Pair<Rect, String>>,
        processor: MslTranslationProcessor,
        provider: MslTranslationProvider,
        output: MutableList<Pair<Rect, String>>,
        shouldStop: () -> Boolean,
        done: (Int) -> Unit,
    ) {
        var failed = 0

        fun nextRegion(regionIndex: Int) {
            if (shouldStop()) return
            if (regionIndex >= items.size) {
                done(failed)
                return
            }
            val (box, source) = items[regionIndex]
            val chunks = processor.plan(source)
            if (chunks.isEmpty()) {
                failed++
                nextRegion(regionIndex + 1)
                return
            }
            val responses = mutableListOf<String>()
            var regionFinished = false

            fun failRegion(error: Throwable?) {
                if (regionFinished || shouldStop()) return
                regionFinished = true
                failed++
                error?.let { android.util.Log.w("MslTranslate", "Translation region failed", it) }
                nextRegion(regionIndex + 1)
            }

            fun nextChunk(chunkIndex: Int) {
                if (regionFinished || shouldStop()) return
                if (chunkIndex >= chunks.size) {
                    val combined = processor.join(chunks, responses)
                    if (combined == null || !MslTranslationText.isUsableTranslation(combined)) {
                        failRegion(IllegalStateException("Empty translation response"))
                    } else {
                        regionFinished = true
                        output += box to combined
                        nextRegion(regionIndex + 1)
                    }
                    return
                }
                val chunk = chunks[chunkIndex]
                processor.cached(chunk.source)?.let {
                    responses += it
                    nextChunk(chunkIndex + 1)
                    return
                }
                val protected = processor.protect(chunk.source)
                provider.translate(
                    protected.text,
                    onSuccess = { translated ->
                        if (shouldStop()) return@translate
                        if (!MslTranslationText.isUsableTranslation(translated)) {
                            failRegion(IllegalStateException("Empty translation response"))
                        } else {
                            val restored = protected.restore(translated)
                            if (restored == null || !MslTranslationText.isUsableTranslation(restored)) {
                                failRegion(IllegalStateException("Empty restored translation response"))
                            } else {
                                processor.cache(chunk.source, restored)
                                responses += restored
                                nextChunk(chunkIndex + 1)
                            }
                        }
                    },
                    onFailure = { if (!shouldStop()) failRegion(it) },
                )
            }
            nextChunk(0)
        }

        nextRegion(0)
    }

    private fun closeTranslator(tr: com.google.mlkit.nl.translate.Translator) {
        runCatching { tr.close() }
    }

    private fun showTranslationModelUnavailable(activity: Activity, error: Throwable) {
        android.util.Log.w("MslTranslate", "ML Kit English-Arabic model unavailable", error)
        if (activity.isFinishing || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) return
        toast(activity, "تعذّر تنزيل أو تجهيز نموذج English → Arabic. افحص اتصال الإنترنت وخدمات Google Play ثم أعد المحاولة.")
    }

    private fun showTranslationUnavailable(activity: Activity, error: Throwable) {
        android.util.Log.w("MslTranslate", "ML Kit English-Arabic translator unavailable", error)
        if (activity.isFinishing || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) return
        toast(activity, "تعذّرت تهيئة ترجمة English → Arabic؛ لم تُعرض نتيجة غير مؤكدة.")
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
