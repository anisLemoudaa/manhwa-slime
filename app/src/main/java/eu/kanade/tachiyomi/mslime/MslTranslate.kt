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
