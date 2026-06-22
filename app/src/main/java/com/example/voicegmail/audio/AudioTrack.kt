package com.example.voicegmail.audio

data class AudioTrack(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val trackNumber: Int = 0
)
