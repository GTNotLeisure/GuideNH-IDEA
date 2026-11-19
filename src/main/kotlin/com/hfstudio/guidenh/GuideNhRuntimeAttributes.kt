package com.hfstudio.guidenh

private val runtimeAttributeCapabilities = mapOf(
    "ResearchLink.id" to "bindings", "ResearchCard.id" to "bindings", "ResearchIcon.id" to "bindings",
    "BindingLink.id" to "bindings", "BindingCard.id" to "bindings", "BindingIcon.id" to "bindings",
    "FluidImage.id" to "fluids", "FluidLink.id" to "fluids", "FluidIcon.id" to "fluids",
    "InputAnnotation.fluid" to "fluids", "ContentTabs.iconFluid" to "fluids", "ContentTabs.icon_fluid" to "fluids",
    "Series.iconFluid" to "fluids", "LineSeries.iconFluid" to "fluids", "Slice.iconFluid" to "fluids",
    "Recipe.fluid" to "fluids", "Usage.fluid" to "fluids", "RecipeFor.fluid" to "fluids",
    "RecipeUsage.fluid" to "fluids", "RecipesFor.fluid" to "fluids", "RecipesUsage.fluid" to "fluids",
    "NeiRecipeLink.id" to "items",
    "ItemLink.id" to "items", "ItemLink.ore" to "ores",
    "ItemImage.id" to "items", "ItemImage.ore" to "ores",
    "BlockImage.id" to "items", "BlockImage.ore" to "ores",
    "Block.id" to "items", "Block.ore" to "ores",
    "PlaceBlock.id" to "items", "PlaceBlock.ore" to "ores",
    "RemoveBlocks.id" to "items", "ReplaceBlock.from" to "items", "ReplaceBlock.to" to "items",
    "Recipe.id" to "recipes", "RecipeFor.id" to "recipes", "RecipeUsage.id" to "recipes", "RecipesFor.id" to "recipes",
    "QuestCard.id" to "quests", "QuestLink.id" to "quests",
    "KeyBind.id" to "keybinds", "KeyBind.action" to "keybinds",
    "CommandLink.command" to "commands", "PlaySound.sound" to "sounds", "SoundLink.sound" to "sounds",
    "Entity.id" to "entities", "ImportStructureLib.controller" to "structurelib", "SubPages.id" to "pages"
)

private val typeCapabilities = mapOf("binding" to "bindings", "fluid" to "fluids", "item" to "items", "ore" to "ores", "page" to "pages")

fun resolveGuideNhRuntimeCapability(tagName: String?, attributeName: String, schema: GuideNhAttributeSchema? = null): String? =
    when {
        tagName.equals("ImportStructureLib", true) && attributeName.lowercase() in setOf("channel", "facing", "rotation", "flip", "piece") -> "structurelib"
        else -> runtimeAttributeCapabilities["${tagName.orEmpty()}.$attributeName"]
    }
        ?: typeCapabilities[schema?.type?.lowercase()]

fun guideNhStaticRuntimeValues(tagName: String?, attributeName: String): List<String> = when {
    tagName.equals("ImportStructureLib", true) && attributeName.equals("facing", true) -> listOf("north", "south", "west", "east", "up", "down")
    tagName.equals("ImportStructureLib", true) && attributeName.equals("rotation", true) -> listOf("normal", "clockwise", "upside down", "counter clockwise")
    tagName.equals("ImportStructureLib", true) && attributeName.equals("flip", true) -> listOf("none", "horizontal", "vertical")
    else -> emptyList()
}

fun guideNhRuntimeFilters(text: String, offset: Int, tagName: String?, attributeName: String): Map<String, String> {
    if (tagName?.startsWith("Research") == true) return mapOf("type" to "thaumcraft")
    if (tagName?.startsWith("Binding") == true) {
        val tag = GuideNhParser.parse(text).tags.firstOrNull { offset in it.range }
        val type = tag?.let(::guideNhBindingType) ?: Regex("""type\s*=\s*["']([^"']*)["']""")
            .find(text.substring(0, offset).substringAfterLast('<'))?.groupValues?.get(1)
        return type?.let { mapOf("type" to it) } ?: emptyMap()
    }
    if (!tagName.equals("ImportStructureLib", true)) return emptyMap()
    val openTag = text.substring(0, offset).substringAfterLast('<')
    if (!openTag.startsWith("ImportStructureLib", true)) return emptyMap()
    fun attribute(name: String): String? = Regex("""\b${Regex.escape(name)}\s*=\s*[\"']([^\"']*)[\"']""", RegexOption.IGNORE_CASE)
        .find(openTag)?.groupValues?.get(1)?.takeIf(String::isNotBlank)
    val controller = attribute("controller") ?: return emptyMap()
    return buildMap {
        put("attribute", attributeName)
        put("controller", controller)
        if (attributeName.equals("facing", true) || attributeName.equals("rotation", true) || attributeName.equals("flip", true)) {
            listOf("facing", "rotation", "flip").filterNot { it.equals(attributeName, true) }
                .forEach { name -> attribute(name)?.let { put(name, it) } }
        }
    }
}

fun resolveGuideNhFrontmatterRuntimeCapability(path: String): String? = mapOf(
    "research_id" to "bindings", "research_ids" to "bindings", "bindings.id" to "bindings",
    "fluid_id" to "fluids", "fluid_ids" to "fluids",
    "navigation.icon_fluid" to "fluids", "navigation.icon_fluids" to "fluids",
    "item_id" to "items", "item_ids" to "items", "ore_ids" to "ores",
    "categories" to "categories", "navigation.parent" to "pages", "navigation.required_mod" to "mods",
    "navigation.required_mods" to "mods", "navigation.excluded_mod" to "mods", "navigation.excluded_mods" to "mods",
    "navigation.icon" to "items", "navigation.icons" to "items"
)[path]
