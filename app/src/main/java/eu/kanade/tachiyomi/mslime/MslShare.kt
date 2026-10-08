@file:Suppress("ktlint:standard:max-line-length")

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
        bg.shader =
            LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF0F0F13.toInt(), 0xFF123A5A.toInt(), Shader.TileMode.CLAMP)
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
