package com.firesky.launcher

/**
 * Text as it should read. Some of what titles arrive with is written for a web page, so a show called
 * "Love, Death & Robots" comes as "Love, Death &amp; Robots". The common named characters and numbered
 * ones are turned back into themselves; anything else that looks like one is left as it is.
 */
internal fun plain(s: String): String {
    if ('&' !in s) return s
    var out = s
    // Twice, for text that was written for a web page twice over: "&amp;amp;".
    repeat(2) {
        if ('&' !in out) return out
        out = ENTITY.replace(out) { m ->
            val name = m.groupValues[1]
            when {
                name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)?.let(::char)
                name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::char)
                else -> NAMED[name.lowercase()]
            } ?: m.value
        }
    }
    return out
}

private fun char(code: Int): String? =
    if (code in 1..0x10FFFF && code !in 0xD800..0xDFFF) String(Character.toChars(code)) else null

private val ENTITY = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});""")

private val NAMED = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "ndash" to "–", "mdash" to "—", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
)
