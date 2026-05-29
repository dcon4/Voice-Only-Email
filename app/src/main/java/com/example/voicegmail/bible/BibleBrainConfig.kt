package com.example.voicegmail.bible

import com.example.voicegmail.BuildConfig

/**
 * Configuration for the Bible Brain (Digital Bible Platform v4) API.
 *
 * To use Bible audio:
 * 1. Request an API key at https://4.dbt.io/api_key/request
 * 2. Set it via:
 *    - Gradle property: bibleBrainApiKey=YOUR_KEY
 *    - Environment variable: BIBLE_BRAIN_API_KEY=YOUR_KEY
 *    - Or replace the default below for local testing
 */
object BibleBrainConfig {
    const val BASE_URL = "https://4.dbt.io/api/"

    /**
     * API key — read from BuildConfig (set via gradle property or env var).
     * Defaults to empty string; the app gracefully tells the user if unconfigured.
     */
    val API_KEY: String get() = BuildConfig.BIBLE_BRAIN_API_KEY

    /**
     * Default Bible ID. ENGKJV = English King James Version.
     * The app uses this to look up available audio filesets.
     */
    const val DEFAULT_BIBLE_ID = "ENGKJV"

    /**
     * Preferred fileset type for audio playback.
     * "audio_drama" includes dramatized readings with sound effects;
     * "audio" is plain narration.
     */
    const val PREFERRED_AUDIO_TYPE = "audio_drama"
    const val FALLBACK_AUDIO_TYPE = "audio"
}
