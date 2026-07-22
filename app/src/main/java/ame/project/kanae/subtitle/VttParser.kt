/*
 * KanaePlayer -
 * Copyright (C) 2026 KanaePlayer Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed WITHOUT ANY WARRANTY; see the
 * GNU General Public License for more details: <https://www.gnu.org/licenses/>.
 */

package ame.project.kanae.subtitle

import android.util.Log

/**
 * Minimal WebVTT (.vtt) parser.
 *
 * Handles:
 *  - Standard  "00:01:23.456 --> 00:01:25.789"
 *  - Short     "01:23.456 --> 01:25.789"           (no hour)
 *  - YouTube   timestamps with optional position tags like "align:start"
 *  - Basic HTML tag stripping (<b>, <i>, <c.color>, <00:00:01.000>, etc.)
 *  - Consecutive blank lines used as block separators
 */
object VttParser {

    private const val TAG = "VttParser"

    // Regex: optional HH: + MM:SS.mmm --> optional HH: + MM:SS.mmm  (+ optional cue settings)
    private val TIMING = Regex(
        """(\d{1,2}:)?(\d{2}):(\d{2}[.,]\d{3})\s*-->\s*(\d{1,2}:)?(\d{2}):(\d{2}[.,]\d{3}).*"""
    )

    // Strip any XML/HTML-like tags (including VTT timestamp tags <00:00:01.500>)
    private val TAG_STRIP = Regex("""<[^>]*>""")

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Parse a raw VTT string and return a list of [SubtitleCue] sorted by start time.
     * Returns an empty list on parse failure.
     */
    fun parse(vtt: String): List<SubtitleCue> {
        return try {
            doParse(vtt)
        } catch (e: Exception) {
            Log.e(TAG, "VTT parse error: ${e.message}")
            emptyList()
        }
    }

    // ── Private ───────────────────────────────────────────────────────

    private fun doParse(vtt: String): List<SubtitleCue> {
        val cues    = mutableListOf<SubtitleCue>()
        val lines   = vtt.lines()
        var i       = 0

        // Skip WEBVTT header line(s)
        while (i < lines.size && !lines[i].trimStart().startsWith("WEBVTT")) i++
        i++ // skip the WEBVTT line itself

        while (i < lines.size) {
            val line = lines[i].trim()

            // Skip blank lines and NOTE / STYLE / REGION blocks
            if (line.isEmpty() || line.startsWith("NOTE") ||
                line.startsWith("STYLE") || line.startsWith("REGION")) {
                i++; continue
            }

            // Optional cue identifier (numeric or text) — skip it
            val nextLine = lines.getOrNull(i + 1)?.trim() ?: ""
            val isIdLine = TIMING.containsMatchIn(nextLine) && !TIMING.containsMatchIn(line)
            if (isIdLine) { i++; continue }

            // Timing line
            val match = TIMING.find(line)
            if (match != null) {
                val startMs = parseTime(match, groupOffset = 0)
                val endMs   = parseTime(match, groupOffset = 3)
                i++

                // Collect text lines until blank line or next timing
                val textLines = mutableListOf<String>()
                while (i < lines.size) {
                    val tl = lines[i].trim()
                    if (tl.isEmpty()) break                          // block end
                    if (TIMING.containsMatchIn(tl)) break           // next cue's timing
                    textLines.add(tl)
                    i++
                }

                val text = textLines.joinToString("\n") { stripTags(it) }.trim()
                if (text.isNotBlank() && endMs > startMs) {
                    cues.add(SubtitleCue(startMs, endMs, text))
                }
                continue
            }

            i++
        }

        // YouTube sometimes produces duplicate cues (rolling captions).
        // Deduplicate: keep only the last cue per unique text that starts at the same ms.
        return cues
            .sortedBy { it.startMs }
            .distinctBy { it.startMs to it.text }
    }

    /**
     * Extract milliseconds from a regex match.
     * Groups layout (1-indexed):  [h?][mm][ss.mmm]  -->  [h?][mm][ss.mmm]
     * groupOffset = 0 for start, 3 for end.
     */
    private fun parseTime(match: MatchResult, groupOffset: Int): Long {
        // groups: 1=h(start), 2=m(start), 3=s(start),  4=h(end), 5=m(end), 6=s(end)
        val hGroup = match.groupValues[groupOffset + 1]
        val mGroup = match.groupValues[groupOffset + 2]
        val sGroup = match.groupValues[groupOffset + 3]

        val h  = hGroup.trimEnd(':').toLongOrNull() ?: 0L
        val m  = mGroup.toLongOrNull() ?: 0L
        // seconds may use '.' or ','
        val s  = sGroup.replace(',', '.').toDoubleOrNull() ?: 0.0
        val ms = (s * 1000).toLong()

        return h * 3_600_000L + m * 60_000L + ms
    }

    /** Remove HTML/VTT tags and decode common entities. */
    private fun stripTags(s: String): String =
        TAG_STRIP.replace(s, "")
            .replace("&amp;",  "&")
            .replace("&lt;",   "<")
            .replace("&gt;",   ">")
            .replace("&nbsp;", " ")
            .replace("\u200B", "") // zero-width space (YouTube)
            .trim()
}
