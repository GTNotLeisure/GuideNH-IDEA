package com.hfstudio.guidenh

import com.intellij.openapi.util.TextRange
import java.util.Base64
import java.util.UUID
import java.nio.ByteBuffer
import java.io.StringReader
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import kotlin.jvm.JvmRecord

private val guideNhBindingFlowPattern = Regex("""(?:[\{,])\s*(type|id|anchor)\s*:\s*("(?:\\.|[^"\\])*"?|'(?:''|[^'])*'?|[^,}\r\n]*)""")
private val guideNhBindingTopLevelPattern = Regex("""(?m)^\S""")
private val guideNhBindingFlowEntryPattern = Regex("""^[ \t]*-[ \t]+\{""")

private fun guideNhBindingYamlScalar(value: String): String {
    return try { (Yaml().compose(StringReader(value)) as? ScalarNode)?.value.orEmpty() }
    catch (_: YAMLException) { value.trim().removePrefix("\"").removePrefix("'") }
}

private fun guideNhBindingYamlRoot(text: String): MappingNode? {
    if (!text.startsWith("---")) return null
    val end = text.indexOf("\n---", 3)
    if (end < 0) return null
    return try { Yaml().compose(StringReader(text.substring(0, end))) as? MappingNode }
    catch (_: YAMLException) { null }
}

private fun guideNhBindingFields(entry: MappingNode): Map<String, ScalarNode> =
    entry.value.mapNotNull { pair ->
        val key = pair.keyNode as? ScalarNode ?: return@mapNotNull null
        val value = pair.valueNode as? ScalarNode ?: return@mapNotNull null
        key.value to value
    }.toMap()

public fun guideNhBindingIdentity(type: String?, rawId: String): String {
    val normalizedType = type.orEmpty().trim().lowercase()
    var id = rawId.trim()
    if (normalizedType == "betterquesting") {
        id = runCatching { UUID.fromString(id).toString() }.getOrElse {
            runCatching {
                val bytes = Base64.getDecoder().decode(id.replace('-', '+').replace('_', '/'))
                require(bytes.size == 16)
                val buffer = ByteBuffer.wrap(bytes)
                UUID(buffer.long, buffer.long).toString()
            }.getOrDefault(id)
        }
    }
    return "$normalizedType\u0000$id"
}

public fun guideNhBindingType(tag: GuideNhTag): String? = when {
    tag.name.startsWith("Research") -> "thaumcraft"
    tag.name.startsWith("Quest") -> "betterquesting"
    tag.name.startsWith("Binding") -> tag.attributes.firstOrNull { it.name == "type" }?.value
    else -> null
}

public fun guideNhFrontmatterBindings(text: String): List<GuideNhReference> {
    val root = guideNhBindingYamlRoot(text) ?: return emptyList()
    val result = mutableListOf<GuideNhReference>()
    for (pair in root.value) {
        val key = (pair.keyNode as? ScalarNode)?.value ?: continue
        if (key == "research_id" || key == "research_ids") {
            val nodes = (pair.valueNode as? SequenceNode)?.value ?: listOf(pair.valueNode)
            for (node in nodes.filterIsInstance<ScalarNode>()) {
                result.add(GuideNhReference(GuideNhReferenceKind.BINDING, node.value,
                    TextRange(text.offsetByCodePoints(0, node.startMark.index), text.offsetByCodePoints(0, node.endMark.index)), key, true, "thaumcraft"))
            }
        } else if (key == "bindings") {
            val entries = (pair.valueNode as? SequenceNode)?.value.orEmpty()
            for (entry in entries.filterIsInstance<MappingNode>()) {
                val fields = guideNhBindingFields(entry)
                val type = fields["type"]?.value ?: continue
                val id = fields["id"] ?: continue
                result.add(GuideNhReference(GuideNhReferenceKind.BINDING, id.value,
                    TextRange(text.offsetByCodePoints(0, id.startMark.index), text.offsetByCodePoints(0, id.endMark.index)), "bindings.id", true, type, fields["anchor"]?.value))
            }
        }
    }
    return result
}

@JvmRecord
public data class GuideNhBindingValueContext(val type: String?, val name: String, val prefix: String,
    val range: TextRange, val quote: Char?)

public fun guideNhBindingValueContext(text: String, offset: Int): GuideNhBindingValueContext? {
    val section = text.lastIndexOf("\nbindings:", offset)
    if (section < 0 || !text.startsWith("---")) return null
    val sectionEnd = guideNhBindingTopLevelPattern.find(text, section + 2)?.range?.first ?: return null
    if (offset >= sectionEnd) return null
    val lineStart = text.lastIndexOf('\n', offset - 1) + 1
    val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
    val line = text.substring(lineStart, lineEnd)
    if (!guideNhBindingFlowEntryPattern.containsMatchIn(line)) return null
    var previousEnd = 0
    val fields = guideNhBindingFlowPattern.findAll(line).takeWhile { field ->
        val boundary = line.subSequence(previousEnd, field.range.first).any { it == '}' || it == '#' }
        previousEnd = field.range.last + 1
        !boundary
    }.toList()
    val type = fields.firstOrNull { it.groupValues[1] == "type" }?.groupValues?.get(2)?.let(::guideNhBindingYamlScalar)
    for (field in fields) {
        val value = field.groups[2] ?: continue
        var start = lineStart + value.range.first
        var end = lineStart + value.range.last + 1
        val quote = text.getOrNull(start).takeIf { it == '\"' || it == '\'' }
        if (quote != null) { start++; if (end > start && text[end - 1] == quote) end-- }
        if (offset !in start..end) continue
        val raw = text.substring(start, offset)
        val prefix = if (quote != null) guideNhBindingYamlScalar("$quote$raw$quote") else raw
        return GuideNhBindingValueContext(type, field.groupValues[1], prefix, TextRange(start, end), quote)
    }
    return null
}

public fun guideNhBindingAnchorOffset(text: String, rawAnchor: String?): Int? {
    val anchor = rawAnchor.orEmpty().removePrefix("#")
    if (anchor.isEmpty()) return null
    var offset = 0
    for (line in text.split('\n')) {
        val heading = Regex("""^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$""").find(line)?.groupValues?.get(1)
        val normalized = heading?.trim()?.lowercase()?.replace(Regex("""[`*_~]"""), "")
            ?.replace(Regex("""[^\p{L}\p{N}\s-]"""), "")?.replace(Regex("""\s+"""), "-")
        if (normalized == anchor || line.contains("{#$anchor}") || Regex("""\bid\s*=\s*["']${Regex.escape(anchor)}["']""").containsMatchIn(line)) return offset
        offset += line.length + 1
    }
    return null
}

public fun guideNhBindingTypeAtOffset(text: String, offset: Int): String? =
    guideNhBindingValueContext(text, offset)?.type
        ?: guideNhFrontmatterBindings(text).firstOrNull { offset in it.range }?.bindingType
