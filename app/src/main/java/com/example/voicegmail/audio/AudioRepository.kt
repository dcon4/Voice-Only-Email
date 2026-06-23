package com.example.voicegmail.audio

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.voicegmail.debug.DebugLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AudioRepository"
private const val PREFS_NAME = "audio_player"
private const val KEY_FOLDERS = "indexed_folders"
private const val INDEX_FILE = "audio_index.json"
private const val MIN_SCORE = 0.3

private val SUPPORTED_EXTENSIONS = setOf(
    ".mp3", ".m4a", ".wma", ".wav", ".flac", ".ogg"
)

@Singleton
class AudioRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val gson = Gson()
    private var cachedTracks: List<AudioTrack> = emptyList()

    // ------------------------------------------------------------------
    // Folder management
    // ------------------------------------------------------------------

    fun getIndexedFolderUris(): List<Uri> {
        val set = prefs.getStringSet(KEY_FOLDERS, emptySet()) ?: emptySet()
        return set.mapNotNull { s ->
            try { Uri.parse(s) } catch (e: Exception) { null }
        }
    }

    fun addFolder(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val folders = getIndexedFolderUris().map { it.toString() }.toMutableSet()
        folders.add(uri.toString())
        prefs.edit().putStringSet(KEY_FOLDERS, folders).apply()
    }

    fun removeFolder(uri: Uri) {
        context.contentResolver.releasePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val folders = getIndexedFolderUris().map { it.toString() }.toMutableSet()
        folders.remove(uri.toString())
        prefs.edit().putStringSet(KEY_FOLDERS, folders).apply()
        deleteIndex()
    }

    fun hasFolders(): Boolean = getIndexedFolderUris().isNotEmpty()

    // ------------------------------------------------------------------
    // Scanning
    // ------------------------------------------------------------------

    suspend fun scanAll(): List<AudioTrack> = withContext(Dispatchers.IO) {
        DebugLogger.log(TAG, "scanAll: starting")
        val uris = getIndexedFolderUris()
        if (uris.isEmpty()) {
            cachedTracks = emptyList()
            saveIndex(emptyList())
            return@withContext emptyList()
        }

        val allTracks = mutableListOf<AudioTrack>()
        for (folderUri in uris) {
            val docFile = DocumentFile.fromTreeUri(context, folderUri)
            if (docFile != null) {
                scanDirectory(docFile, allTracks)
            }
        }

        // Sort: album then track number, fallback to title
        allTracks.sortWith(compareBy({ it.album }, { it.trackNumber }, { it.title }))

        cachedTracks = allTracks.toList()
        saveIndex(cachedTracks)
        DebugLogger.log(TAG, "scanAll: indexed ${cachedTracks.size} tracks")
        cachedTracks
    }

    private fun scanDirectory(dir: DocumentFile, results: MutableList<AudioTrack>) {
        val files = dir.listFiles()
        for (file in files) {
            if (file.isDirectory) {
                scanDirectory(file, results)
            } else {
                val name = file.name?.lowercase() ?: continue
                if (SUPPORTED_EXTENSIONS.any { name.endsWith(it) }) {
                    val track = readTags(file.uri, file.name ?: "Unknown")
                    if (track != null) results.add(track)
                }
            }
        }
    }

    private fun readTags(uri: Uri, fileName: String): AudioTrack? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: fileName.removeSuffix(fileName.substringAfterLast('.', ""))
                    .removeSuffix(".")
                    .trim()
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
                ?: "Unknown Artist"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }
                ?: "Unknown Album"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.toIntOrNull() ?: 0

            AudioTrack(
                uri = uri.toString(),
                title = title,
                artist = artist,
                album = album,
                duration = duration,
                trackNumber = trackNumber
            )
        } catch (e: Exception) {
            DebugLogger.log(TAG, "readTags: skipping $fileName — ${e.message}")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    fun search(query: String): AudioTrack? {
        ensureLoaded()
        val q = query.trim().lowercase()
        if (q.isBlank()) return null

        var bestScore = 0.0
        var bestTrack: AudioTrack? = null

        for (track in cachedTracks) {
            val score = maxOf(
                scoreMatch(q, track.title.lowercase()),
                scoreMatch(q, track.artist.lowercase()),
                scoreMatch(q, track.album.lowercase())
            )
            if (score > bestScore) {
                bestScore = score
                bestTrack = track
            }
        }

        return if (bestScore >= MIN_SCORE) bestTrack else null
    }

    fun searchAll(query: String): List<AudioTrack> {
        ensureLoaded()
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()

        return cachedTracks
            .map { track ->
                val score = maxOf(
                    scoreMatch(q, track.title.lowercase()),
                    scoreMatch(q, track.artist.lowercase()),
                    scoreMatch(q, track.album.lowercase())
                )
                track to score
            }
            .filter { it.second >= MIN_SCORE }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    fun getTracksByAlbum(album: String): List<AudioTrack> {
        ensureLoaded()
        return cachedTracks.filter { it.album.equals(album, ignoreCase = true) }
    }

    fun getTracksByArtist(artist: String): List<AudioTrack> {
        ensureLoaded()
        return cachedTracks.filter { it.artist.equals(artist, ignoreCase = true) }
    }

    fun allTracks(): List<AudioTrack> {
        ensureLoaded()
        return cachedTracks
    }

    fun trackCount(): Int {
        ensureLoaded()
        return cachedTracks.size
    }

    // ------------------------------------------------------------------
    // Scoring
    // ------------------------------------------------------------------

    private fun scoreMatch(query: String, target: String): Double {
        if (target == query) return 1.0
        if (wordBoundaryContains(target, query)) return 0.9
        if (wordBoundaryContains(query, target)) return 0.85
        val qWords = query.split(" ").filter { it.isNotBlank() }
        val tWords = target.split(" ").filter { it.isNotBlank() }
        if (qWords.isEmpty()) return 0.0
        val matchCount = qWords.count { qw -> tWords.any { it == qw } }
        if (matchCount == qWords.size) return 0.9
        return 0.5 * matchCount.toDouble() / qWords.size
    }

    private fun wordBoundaryContains(haystack: String, needle: String): Boolean {
        return Regex("\\b${Regex.escape(needle)}\\b").containsMatchIn(haystack)
    }

    // ------------------------------------------------------------------
    // Index persistence
    // ------------------------------------------------------------------

    private fun ensureLoaded() {
        if (cachedTracks.isEmpty()) {
            cachedTracks = loadIndex()
        }
    }

    private fun indexFile(): File = File(context.filesDir, INDEX_FILE)

    private fun saveIndex(tracks: List<AudioTrack>) {
        try {
            val json = gson.toJson(tracks)
            indexFile().writeText(json)
        } catch (e: Exception) {
            DebugLogger.log(TAG, "saveIndex: ${e.message}")
        }
    }

    private fun loadIndex(): List<AudioTrack> {
        return try {
            val file = indexFile()
            if (!file.exists()) return emptyList()
            val json = file.readText()
            val type = object : TypeToken<List<AudioTrack>>() {}.type
            gson.fromJson<List<AudioTrack>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            DebugLogger.log(TAG, "loadIndex: ${e.message}")
            emptyList()
        }
    }

    private fun deleteIndex() {
        cachedTracks = emptyList()
        try { indexFile().delete() } catch (_: Exception) {}
    }
}
