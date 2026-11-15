package com.hfstudio.guidenh

/** Locates item-stack attributes supported by GuideNH item picker and editor drops. */
data class GuideNhItemStackContext(
    val tagName: String,
    val attributeName: String,
    val tagStart: Int,
    val tagEndExclusive: Int,
    val valueStart: Int,
    val valueEndExclusive: Int,
    val value: String,
    val capability: String = "items"
)

object GuideNhItemStackContexts {
    private val attributeTargets = mapOf(
        "fluidimage" to setOf("id"), "fluidlink" to setOf("id"), "fluidicon" to setOf("id"),
        "inputannotation" to setOf("item", "fluid"),
        "recipe" to setOf("id", "fluid"), "usage" to setOf("id", "fluid"), "recipefor" to setOf("id", "fluid"),
        "recipeusage" to setOf("id", "fluid"), "recipesfor" to setOf("id", "fluid"), "recipesusage" to setOf("id", "fluid"),
        "contenttabs" to setOf("iconitem", "icon_item", "iconfluid", "icon_fluid"),
        "series" to setOf("icon", "iconfluid"), "lineseries" to setOf("icon", "iconfluid"), "slice" to setOf("icon", "iconfluid"),
        "itemlink" to setOf("id"),
        "itemimage" to setOf("id"),
        "blockimage" to setOf("id"),
        "block" to setOf("id"),
        "placeblock" to setOf("id"),
        "removeblocks" to setOf("id"),
        "replaceblock" to setOf("from", "to")
    )
    private val tagPattern = Regex("<([A-Za-z][A-Za-z0-9]*)\\b[^<>]*?>")
    private val attributePattern = Regex("([A-Za-z_][\\w.-]*)\\s*=\\s*(?:\\\"([^\\\"]*)\\\"|'([^']*)')")

    fun findAt(text: CharSequence, offset: Int): GuideNhItemStackContext? {
        val source = text.toString()
        return tagPattern.findAll(source).asSequence().flatMap { tag ->
            val tagName = tag.groupValues[1]
            val targetAttributes = attributeTargets[tagName.lowercase()].orEmpty()
            if (targetAttributes.isEmpty()) return@flatMap emptySequence()
            attributePattern.findAll(tag.value).asSequence().mapNotNull { attribute ->
                val attributeName = attribute.groupValues[1]
                if (attributeName.lowercase() !in targetAttributes) return@mapNotNull null
                val valueGroup = if (attribute.groups[2] != null) attribute.groups[2]!! else attribute.groups[3]!!
                GuideNhItemStackContext(
                    tagName = tagName,
                    attributeName = attributeName,
                    tagStart = tag.range.first,
                    tagEndExclusive = tag.range.last + 1,
                    valueStart = tag.range.first + valueGroup.range.first,
                    valueEndExclusive = tag.range.first + valueGroup.range.last + 1,
                    value = valueGroup.value,
                    capability = if (tagName.startsWith("Fluid", true) || attributeName.contains("fluid", true)) "fluids" else "items"
                )
            }
        }.firstOrNull { context -> offset in context.valueStart..context.valueEndExclusive }
    }
}
