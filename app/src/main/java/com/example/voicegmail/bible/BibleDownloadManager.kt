package com.example.voicegmail.bible

import com.example.voicegmail.debug.DebugLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BibleDownloadManager"
private const val MIN_DELAY_MS = 3500L  // 1 request per 3.5s (well under 15/30s limit)

/**
 * State of an offline download for a translation.
 */
sealed class DownloadState {
    data object NotDownloaded : DownloadState()
    data class Downloading(val progress: Float, val detail: String) : DownloadState() // 0..1
    data object Completed : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * Orchestrates bulk download of all chapters for a Bible translation.
 * Rate-limited to ~1 request per 2 seconds to respect bible-api.com limits.
 */
@Singleton
class BibleDownloadManager @Inject constructor(
    private val api: BibleApiService,
    private val offlineStorage: BibleOfflineStorage
) {
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states

    private val activeJobs = mutableMapOf<String, Job>()
    private var globalDownloadJob: Job? = null

    fun getState(translation: String): DownloadState {
        return _states.value[translation] ?: DownloadState.NotDownloaded
    }

    /**
     * Start downloading all chapters for [translation].
     * Only one translation can download at a time globally (rate limiting).
     * [scope] is the coroutine scope (usually viewModelScope).
     */
    fun startDownload(translation: String, scope: CoroutineScope) {
        if (activeJobs.containsKey(translation)) {
            DebugLogger.log(TAG, "Download already in progress for $translation")
            return
        }
        if (globalDownloadJob != null) {
            DebugLogger.log(TAG, "Another translation is downloading — cannot start $translation")
            return
        }

        val job = scope.launch(Dispatchers.IO) {
            _states.value = _states.value + (translation to DownloadState.Downloading(0f, "Starting..."))
            try {
                // Phase 1: enumerate books and chapter counts
                val booksResponse = api.getBooks(translation)
                val totalChapters = mutableListOf<Pair<String, Int>>()

                for (book in booksResponse.books) {
                    if (!isActive) break
                    val chapters = api.getChapters(translation, book.id)
                    for (ch in chapters.chapters) {
                        if (!isActive) break
                        totalChapters.add(book.id to ch.chapter)
                    }
                    delay(MIN_DELAY_MS)
                }

                val total = totalChapters.size
                if (total == 0) {
                    _states.value = _states.value + (translation to DownloadState.Completed)
                    return@launch
                }

                // Phase 2: download each chapter with rate limiting
                for ((i, pair) in totalChapters.withIndex()) {
                    if (!isActive) {
                        DebugLogger.log(TAG, "Download cancelled for $translation")
                        return@launch
                    }
                    val (bookId, chapter) = pair

                    if (!offlineStorage.isChapterCached(translation, bookId, chapter)) {
                        val resp = api.getChapter(translation, bookId, chapter)
                        val json = buildJsonForCache(resp)
                        offlineStorage.saveChapter(translation, bookId, chapter, json)
                    }

                    val progress = (i + 1).toFloat() / total
                    _states.value = _states.value + (translation to
                        DownloadState.Downloading(progress, "$bookId chapter $chapter"))

                    if (i < total - 1 && isActive) {
                        delay(MIN_DELAY_MS)
                    }
                }

                _states.value = _states.value + (translation to DownloadState.Completed)
                DebugLogger.log(TAG, "Download complete for $translation ($total chapters)")
            } catch (e: CancellationException) {
                DebugLogger.log(TAG, "Download cancelled for $translation")
                throw e
            } catch (e: Exception) {
                DebugLogger.log(TAG, "Download error for $translation: ${e.message}")
                _states.value = _states.value + (translation to
                    DownloadState.Error(e.message ?: "Unknown error"))
            } finally {
                activeJobs.remove(translation)
                globalDownloadJob = null
            }
        }

        activeJobs[translation] = job
        globalDownloadJob = job
    }

    /** Cancel an in-progress download for [translation]. */
    fun cancelDownload(translation: String) {
        activeJobs[translation]?.cancel()
        activeJobs.remove(translation)
        // Reset state to NotDownloaded (or keep partial — user expectation is reset)
        _states.value = _states.value + (translation to DownloadState.NotDownloaded)
    }

    /** Delete offline data for a translation and reset state. */
    fun deleteDownload(translation: String) {
        cancelDownload(translation)
        offlineStorage.deleteTranslation(translation)
        _states.value = _states.value + (translation to DownloadState.NotDownloaded)
    }

    /** Refresh states from disk (e.g. on app restart). */
    suspend fun refreshStates(translation: String, totalChapters: Int) {
        val cached = offlineStorage.countCachedChapters(translation)
        val state: DownloadState = if (cached >= totalChapters) {
            DownloadState.Completed
        } else if (cached > 0) {
            DownloadState.Downloading(cached.toFloat() / totalChapters,
                "$cached / $totalChapters chapters cached")
        } else {
            DownloadState.NotDownloaded
        }
        _states.value = _states.value + (translation to state)
    }

    /** Set an initial state (used on load). */
    fun setState(translation: String, state: DownloadState) {
        _states.value = _states.value + (translation to state)
    }

    private fun buildJsonForCache(resp: ChapterResponse): String {
        val verses = resp.verses.joinToString(",") { v ->
            """{"book_id":"${v.book_id}","book":"${v.book}","chapter":${v.chapter},"verse":${v.verse},"text":"${v.text.replace("\"", "\\\"")}"}"""
        }
        return """{"verses":[$verses]}"""
    }
}
