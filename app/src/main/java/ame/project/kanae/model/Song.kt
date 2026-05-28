package ame.project.kanae.model

import java.util.UUID

data class Song(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val youtubeUrl: String,
    val thumbnail: String? = null,
    val duration: Int = 0,           // seconds
    val channel: String? = null,
    val requestedBy: String? = null, // TikTok username
    val addedAt: Long = System.currentTimeMillis()
) {
    /** Formatted duration string e.g. "3:45" */
    val durationFormatted: String
        get() {
            val m = duration / 60
            val s = duration % 60
            return "%d:%02d".format(m, s)
        }
}
