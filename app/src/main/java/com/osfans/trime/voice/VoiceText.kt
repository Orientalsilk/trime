// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.voice

/**
 * Text rules for voice input, kept free of Android classes so they can be unit tested.
 *
 * The recognizer returns one unpunctuated segment per pause. A punctuation model decides much
 * better when it sees the neighbouring segment, so the newest segment is held back (still shown
 * as a preview) until the next one arrives; the pair is punctuated together and only the older
 * segment, now with its true boundary punctuation, is committed.
 */
object VoiceText {
    private const val SENTENCE_END = "。？！.?!"
    private const val ANY_PUNCT = "，。？！、；：,.?!;:"

    private fun isContent(c: Char) = c.isLetterOrDigit()

    private fun contentCount(s: String) = s.count(::isContent)

    /**
     * Split [punctuated] (the model's output for head + tail) after the head's last letter or digit,
     * keeping the punctuation that directly follows it with the head. Returns null when the output
     * does not contain the expected amount of text (the model rewrote something).
     */
    fun splitAfter(
        punctuated: String,
        headRaw: String,
    ): Pair<String, String>? {
        val need = contentCount(headRaw)
        if (need == 0) return null
        var seen = 0
        var i = 0
        while (i < punctuated.length && seen < need) {
            if (isContent(punctuated[i])) seen++
            i++
        }
        if (seen < need) return null
        // Absorb the punctuation (and spaces around it) that follows the head's last character.
        while (i < punctuated.length && !isContent(punctuated[i])) {
            if (punctuated[i] in ANY_PUNCT) {
                i++
            } else if (punctuated[i].isWhitespace() && i + 1 < punctuated.length && punctuated[i + 1] in ANY_PUNCT) {
                i++
            } else {
                break
            }
        }
        return punctuated.substring(0, i).trim() to punctuated.substring(i).trim()
    }

    private fun lastIsPunct(s: String) = s.isNotEmpty() && s.last() in ANY_PUNCT

    /** Join [held] and [next] with a space only between two Latin words. */
    fun glue(
        held: String,
        next: String,
    ): String {
        if (held.isEmpty()) return next
        val a = held.last()
        val b = next.firstOrNull() ?: return held
        val latin = { c: Char -> c.isLetterOrDigit() && c.code < 0x2E80 }
        return if (latin(a) && latin(b)) "$held $next" else held + next
    }

    /**
     * Called when a new segment [next] arrives while [held] is still waiting. Returns the text that
     * can now be committed for [held] (with its closing punctuation).
     */
    fun commitHeld(
        held: String,
        next: String,
        punctuate: (String) -> String,
    ): String {
        if (held.isBlank()) return ""
        val joined = punctuate(glue(held, next))
        val split = splitAfter(joined, held)
        val head = split?.first ?: punctuate(held)
        // A pause in the middle of a thought: end with a comma unless the model closed the sentence.
        return if (lastIsPunct(head)) head else "$head，"
    }

    /** The last segment of a session always ends the sentence. */
    fun finishHeld(
        held: String,
        punctuate: (String) -> String,
    ): String {
        if (held.isBlank()) return ""
        val out = punctuate(held).trim()
        return when {
            out.isEmpty() -> ""
            out.last() in SENTENCE_END -> out
            out.last() in ANY_PUNCT -> out.dropLast(1) + if (out.any { c -> c.code >= 0x2E80 }) "。" else "."
            else -> out + if (out.any { c -> c.code >= 0x2E80 }) "。" else "."
        }
    }
}
