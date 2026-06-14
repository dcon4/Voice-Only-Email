package com.example.voicegmail.bible

import android.content.Context
import com.example.voicegmail.debug.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BibleOfflineStorage"
private const val CACHE_DIR = "bible_cache"

/**
 * File-based cache for Bible chapter data.
 * Chapters are stored as JSON files at:
 *   {filesDir}/bible_cache/{translation}/{bookId}/{chapter}.json
 *
 * Each file contains the raw JSON from the bible-api.com chapter endpoint.
 */
@Singleton
class BibleOfflineStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val baseDir: File get() = File(context.filesDir, CACHE_DIR)

    /** Path for a chapter file. */
    private fun chapterFile(translation: String, bookId: String, chapter: Int): File {
        return File(baseDir, "$translation/$bookId/$chapter.json")
    }

    /** True if the chapter is cached on disk. */
    fun isChapterCached(translation: String, bookId: String, chapter: Int): Boolean {
        return chapterFile(translation, bookId, chapter).exists()
    }

    /** Read a chapter's raw JSON from disk cache, or null if not cached. */
    fun readChapter(translation: String, bookId: String, chapter: Int): String? {
        val file = chapterFile(translation, bookId, chapter)
        if (!file.exists()) return null
        return try {
            file.readText()
        } catch (e: Exception) {
            DebugLogger.log(TAG, "Error reading cached chapter $translation/$bookId/$chapter: ${e.message}")
            null
        }
    }

    /** Save a chapter's raw JSON to disk cache. */
    fun saveChapter(translation: String, bookId: String, chapter: Int, json: String) {
        try {
            val file = chapterFile(translation, bookId, chapter)
            file.parentFile?.mkdirs()
            file.writeText(json)
            DebugLogger.verbose(TAG, "Cached $translation/$bookId/$chapter (${json.length} bytes)")
        } catch (e: Exception) {
            DebugLogger.log(TAG, "Error caching chapter $translation/$bookId/$chapter: ${e.message}")
        }
    }

    /** Delete all cached data for a translation. */
    fun deleteTranslation(translation: String) {
        val dir = File(baseDir, translation)
        if (dir.exists()) {
            dir.deleteRecursively()
            DebugLogger.log(TAG, "Deleted cache for translation $translation")
        }
    }

    /** Count cached chapters for a translation. */
    fun countCachedChapters(translation: String): Int {
        val dir = File(baseDir, translation)
        if (!dir.exists()) return 0
        return dir.walkTopDown().count { it.extension == "json" }
    }

    /** Check if a full translation is completely cached (all chapters). */
    fun isTranslationComplete(translation: String, totalChapters: Int): Boolean {
        return countCachedChapters(translation) >= totalChapters
    }

    /** Delete all cached Bible data. */
    fun clearAll() {
        if (baseDir.exists()) {
            baseDir.deleteRecursively()
            DebugLogger.log(TAG, "Cleared all Bible cache")
        }
    }
}
