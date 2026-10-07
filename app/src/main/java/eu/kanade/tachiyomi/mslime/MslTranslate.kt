package eu.kanade.tachiyomi.mslime

import android.app.Activity
import android.app.AlertDialog
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
import android.view.PixelCopy
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.PopupMenu
import android.view.View
import android.widget.EditText
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

/** ترجمة ذكية عبر Anthropic API: طلب واحد لكل صفحة. */
object MslAi {
    private const val MODEL = "claude-haiku-4-5-20251001"
    private const val SYSTEM =
        "You translate speech bubbles of manhwa/manga pages from English into natural, fluent Arabic " +
            "that sounds like real comics: casual and lively for dialogue, short for sound effects. " +
            "Use context from the whole page to fix OCR mistakes and keep names and terms consistent. " +
            "The user message is a JSON array of strings in reading order. " +
            "Reply with ONLY a JSON array of the same length containing the Arabic translations, no other text. " +
            "If an item is not real dialogue (watermark, site name, noise), return an empty string for it."

    fun translate(key: String, texts: List<String>): List<String>? =
        if (key.startsWith("AIza")) translateGemini(key, texts) else translateClaude(key, texts)

    private fun translateGemini(key: String, texts: List<String>): List<String>? {
        return try {
            val arr = JSONArray()
            texts.forEach { arr.put(it) }
            val sys = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM)))
            val user = JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", arr.toString())))
            val body = JSONObject()
                .put("systemInstruction", sys)
                .put("contents", JSONArray().put(user))
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
                .toString()
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("x-goog-api-key", key)
            conn.setRequestProperty("content-type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val txt = stream.bufferedReader().readText()
            if (code !in 200..299) return null
            var out = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
            out = out.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val a = JSONArray(out)
            List(a.length()) { a.getString(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun translateClaude(key: String, texts: List<String>): List<String>? {
        return try {
            val arr = JSONArray()
            texts.forEach { arr.put(it) }
            val msg = JSONObject().put("role", "user").put("content", arr.toString())
            val body = JSONObject()
                .put("model", MODEL)
                .put("max_tokens", 3000)
                .put("system", SYSTEM)
                .put("messages", JSONArray().put(msg))
                .toString()
            val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.setRequestProperty("content-type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val txt = stream.bufferedReader().readText()
            if (code !in 200..299) return null
            var out = JSONObject(txt).getJSONArray("content").getJSONObject(0).getString("text").trim()
            out = out.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val a = JSONArray(out)
            List(a.length()) { a.getString(it) }
        } catch (e: Exception) {
            null
        }
    }
}

object MslTranslate {
    private fun toast(a: Activity, m: String) = Toast.makeText(a, m, Toast.LENGTH_SHORT).show()

    private fun prefs(a: Activity) = a.getSharedPreferences("msl", Context.MODE_PRIVATE)

    fun askKey(activity: Activity) {
        val et = EditText(activity)
        et.hint = "AIza... أو sk-ant-..."
        et.setSingleLine()
        et.setText(prefs(activity).getString("ai_key", ""))
        AlertDialog.Builder(activity)
            .setTitle("مفتاح الترجمة الذكية (Gemini مجاني أو Claude)")
            .setView(et)
            .setPositiveButton("حفظ") { _, _ ->
                prefs(activity).edit().putString("ai_key", et.text.toString().trim()).apply()
                toast(activity, "تم الحفظ")
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

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
                    translateItems(activity, bmp, items)
                }
            }
            .addOnFailureListener { toast(activity, "فشلت قراءة النص: ${it.message}") }
    }

    private fun translateItems(activity: Activity, bmp: Bitmap, items: List<Pair<Rect, String>>) {
        val key = prefs(activity).getString("ai_key", "").orEmpty()
        if (key.isBlank()) {
            toast(activity, "ترجمة آلية. للترجمة الذكية: اضغط مطولاً على زر ع وأدخل مفتاح API")
            mlKit(activity, bmp, items)
            return
        }
        toast(activity, "جارٍ الترجمة الذكية...")
        Thread {
            val res = MslAi.translate(key, items.map { it.second })
            activity.runOnUiThread {
                if (res != null && res.size == items.size) {
                    show(activity, bmp, items.mapIndexed { i, p -> p.first to res[i] })
                } else {
                    toast(activity, "تعذّرت الترجمة الذكية، سأستخدم الترجمة الآلية")
                    mlKit(activity, bmp, items)
                }
            }
        }.start()
    }

    private fun mlKit(activity: Activity, bmp: Bitmap, items: List<Pair<Rect, String>>) {
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
        tp.color = 0xFF111111.toInt()
        tp.typeface = MslFont.get(activity)
        for ((box, text) in items) {
            if (text.isBlank()) continue
            val r = Rect(box)
            r.inset(-8, -8)
            c.drawRoundRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), 18f, 18f, bg)
            var size = 44f
            var layout = layoutFor(text, tp, size, r.width() - 12)
            while (layout.height > r.height() && size > 12f) {
                size -= 2f
                layout = layoutFor(text, tp, size, r.width() - 12)
            }
            c.save()
            c.translate(r.left + 6f, r.top + (r.height() - layout.height).coerceAtLeast(0) / 2f)
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
