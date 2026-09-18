package com.archcomplex.aplayer

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val uri: String,
    val duration: Long = 0L,
    val size: Long = 0L,
    val bitrate: String = "—",
    var favorite: Boolean = false
)
