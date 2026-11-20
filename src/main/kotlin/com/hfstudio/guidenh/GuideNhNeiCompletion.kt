package com.hfstudio.guidenh

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

open class GuideNhNeiCompletionContext(
    val start: Int,
    val end: Int,
    val prefix: String,
    val choices: List<String>,
    val capability: String? = null
)

fun guideNhNeiCompletionContext(text: String, offset: Int): GuideNhNeiCompletionContext? {
    val lineStart = text.lastIndexOf('\n', offset - 1) + 1
    var start = -1
    Regex("&\\[[^\\]\\r\\n]*\\]\\(").findAll(text.substring(lineStart, offset)).forEach { match ->
        val actionStart = lineStart + match.range.first
        var escapes = 0
        var index = actionStart - 1
        while (index >= 0 && text[index--] == '\\') escapes++
        if (escapes % 2 == 0) start = lineStart + match.range.last + 1
    }
    if (start < 0) return null
    var end = start
    while (end < text.length && text[end] != ')' && !text[end].isWhitespace()) end++
    if (offset > end) return null
    val uri = text.substring(start, end)
    val typed = text.substring(start, offset)
    if (typed.isNotEmpty() && !"nei:".startsWith(typed, true) && !typed.startsWith("nei:", true)) return null
    val query = uri.indexOf('?')
    if (query < 0 || offset <= start + query) {
        return GuideNhNeiCompletionContext(start, if (query < 0) end else start + query, typed,
            listOf("nei:handler", "nei:recipes", "nei:usage").map { it + if (query < 0) "?" else "" })
    }
    val mode = uri.substring(4, query).lowercase()
    val segmentStart = maxOf(start + query, text.lastIndexOf('&', offset - 1)) + 1
    var segmentEnd = segmentStart
    while (segmentEnd < end && text[segmentEnd] != '&') segmentEnd++
    val equals = text.indexOf('=', segmentStart)
    if (equals in segmentStart until segmentEnd && offset > equals) {
        val key = text.substring(segmentStart, equals)
        val capability = when {
            key == "handlerId" -> "neiHandlers"
            key == "id" && mode != "handler" -> "items"
            else -> null
        }
        val raw = text.substring(equals + 1, offset)
        val prefix = try {
            URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            raw
        }
        return GuideNhNeiCompletionContext(equals + 1, segmentEnd, prefix, emptyList(), capability)
    }
    val existing = mutableSetOf<String>()
    var relativeStart = query + 1
    uri.substring(query + 1).split('&').forEach { segment ->
        if (relativeStart != segmentStart - start) existing += segment.substringBefore('=')
        relativeStart += segment.length + 1
    }
    val hasEquals = equals in segmentStart until segmentEnd
    val keys = if (mode == "handler") listOf("handlerId") else listOf("id", "handlerId")
    return GuideNhNeiCompletionContext(segmentStart, if (hasEquals) equals else segmentEnd, text.substring(segmentStart, offset),
        keys.filterNot { it in existing }.map { it + if (hasEquals) "" else "=" })
}

fun encodeGuideNhNeiCompletionValue(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
