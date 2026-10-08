package eu.kanade.tachiyomi.novel

import android.content.Context

object NovelManagerHolder {
    @Volatile private var manager: NovelSourceManager? = null

    fun get(context: Context): NovelSourceManager {
        return manager ?: synchronized(this) {
            manager ?: NovelSourceManager(context.applicationContext).also { manager = it }
        }
    }
}
