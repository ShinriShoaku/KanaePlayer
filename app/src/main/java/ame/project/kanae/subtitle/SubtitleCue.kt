package ame.project.kanae.subtitle

/**
 * One subtitle cue — a single line (or block) of text with a time range.
 *
 * @param startMs  cue start time in milliseconds
 * @param endMs    cue end time in milliseconds
 * @param text     display text (HTML tags stripped)
 */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)
