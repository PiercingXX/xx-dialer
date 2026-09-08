package com.piercingxx.xxdialer.ui

/**
 * White Nerd Font / Font Awesome PUA marks for Star, Business, Family, and
 * Block. Paint-only — the four codepoints are what JetBrains Mono Nerd maps
 * at `@font/font_body`. Pure JVM, no android.*.
 */
object GroupGlyphs {

    /** nf-fa-star */
    const val STAR = "\uF005"

    /** nf-fa-briefcase */
    const val BUSINESS = "\uF0B1"

    /** nf-fa-users */
    const val FAMILY = "\uF0C0"

    /** nf-fa-ban */
    const val BLOCK = "\uF05E"

    fun ofName(name: String): String? = when (name.trim().lowercase()) {
        "star", "starred" -> STAR
        "business", "biz" -> BUSINESS
        "family" -> FAMILY
        "block", "blocked" -> BLOCK
        else -> null
    }

    fun prefix(name: String): String {
        val glyph = ofName(name)
        return if (glyph == null) name else "$glyph $name"
    }

    fun labeledList(names: Iterable<String>, empty: String): String {
        val labeled = names.map(::prefix)
        return if (labeled.isEmpty()) empty else labeled.joinToString(" · ")
    }

    fun marks(
        starred: Boolean,
        business: Boolean,
        family: Boolean,
        blocked: Boolean,
    ): String = buildString {
        fun add(glyph: String) {
            if (isNotEmpty()) append(' ')
            append(glyph)
        }
        if (starred) add(STAR)
        if (business) add(BUSINESS)
        if (family) add(FAMILY)
        if (blocked) add(BLOCK)
    }

    fun withTitle(marks: String, title: String): String =
        if (marks.isEmpty()) title else "$marks $title"
}
