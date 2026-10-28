package eu.kanade.tachiyomi.mslime

import android.content.Context
import mihon.app.di.appGraph

/** User-controlled policy for filtering works tagged as adult or NSFW. */
object MslAdultContentFilter {
    fun isEnabled(context: Context): Boolean = context.appGraph.basePreferences.hideAdultContent.get()
}
