package com.hfstudio.guidenh

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.LocalFileSystem
import java.net.URI
import java.nio.file.Paths
import com.intellij.openapi.fileEditor.FileDocumentManager
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.intellij.plugins.markdown.extensions.CodeFenceGeneratingProvider
import org.intellij.plugins.markdown.extensions.MarkdownBrowserPreviewExtension
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanel
import org.intellij.plugins.markdown.ui.preview.ResourceProvider
import com.hfstudio.MyMessageBundle
import java.util.Base64
import com.google.gson.Gson

/** Renders GuideNH fenced and inline Markdown extensions inside IDEA's built-in preview. */
class GuideNhFencePreviewProvider : CodeFenceGeneratingProvider {
    override fun isApplicable(language: String): Boolean = language.lowercase() in setOf("csv", "filetree", "funcgraph")

    override fun generateHtml(language: String, raw: String, node: ASTNode): String = when (language.lowercase()) {
        "csv" -> csv(raw)
        "filetree" -> "<pre class=\"guidenh-filetree\">${escape(raw)}</pre>"
        else -> functionGraph(raw)
    }

    private fun csv(text: String): String {
        val rows = text.lineSequence().filter { it.isNotBlank() }.map { it.split(',') }.toList()
        if (rows.isEmpty()) return "<div class=\"guidenh-empty\">${escape(MyMessageBundle.message("preview.csv.empty"))}</div>"
        return buildString {
            append("<table class=\"guidenh-csv\">")
            rows.forEachIndexed { index, row ->
                append("<tr>")
                row.forEach { cell -> append(if (index == 0) "<th>" else "<td>").append(escape(cell.trim())).append(if (index == 0) "</th>" else "</td>") }
                append("</tr>")
            }
            append("</table>")
        }
    }

    private fun functionGraph(text: String): String {
        // The browser extension turns this neutral container into the same local SVG
        // preview used for <FunctionGraph>. Keeping source text as content avoids an
        // additional serialization format and preserves special characters verbatim.
        return "<guidenh-funcgraph>${escape(text)}</guidenh-funcgraph>"
    }
}

class GuideNhMarkdownPreviewExtensionProvider : MarkdownBrowserPreviewExtension.Provider {
    override fun createBrowserExtension(panel: MarkdownHtmlPanel): MarkdownBrowserPreviewExtension = GuideNhMarkdownPreviewExtension(panel)
}

public open class GuideNhMarkdownPreviewExtension(private val panel: MarkdownHtmlPanel) : MarkdownBrowserPreviewExtension, ResourceProvider, Disposable {
    private val gson = Gson()
    private val imageResourcePrefix = "guidenh-image/"
    private val documentContextUrl = "guidenh-preview-context.js"
    private val scriptUrl = "guidenh-markdown-preview.js"
    private val styleUrl = "guidenh-markdown-preview.css"

    override val scripts: List<String> get() = listOf(documentContextUrl, scriptUrl)
    override val styles: List<String> get() = listOf(styleUrl)
    override val resourceProvider: ResourceProvider get() = this
    override fun canProvide(resourceName: String): Boolean = resourceName == documentContextUrl || resourceName == scriptUrl || resourceName == styleUrl || resourceName.startsWith(imageResourcePrefix)
    override fun loadResource(resourceName: String): ResourceProvider.Resource = when (resourceName) {
        documentContextUrl -> ResourceProvider.Resource(documentContextScript().toByteArray(Charsets.UTF_8), "text/javascript")
        scriptUrl -> resource("/guidenh/preview/$scriptUrl", "text/javascript")
        styleUrl -> resource("/guidenh/preview/$styleUrl", "text/css")
        else -> imageResource(resourceName) ?: ResourceProvider.Resource(ByteArray(0), "text/plain")
    }
    override fun dispose() = Unit

    private fun documentContextScript(): String {
        val documentUrl = panel.virtualFile?.url.orEmpty()
        val source = documentSource()
        val imageReferences = documentImageReferences(source).joinToString(",") { "\"${escapeJavaScript(it)}\"" }
        val (bindingTargets, bindingPreviews) = documentBindingContext(source)
        // Browser-extension resources are served by IDEA's panel-level aggregating provider.
        // Resolve from the preview page itself so its already registered provider hash is kept.
        return """
            (function () {
              window.__GUIDENH_DOCUMENT_URL__="${escapeJavaScript(documentUrl)}";
              window.__GUIDENH_IMAGE_URL__=new URL("$imageResourcePrefix", document.baseURI).toString();
              window.__GUIDENH_IMAGE_REFERENCES__=[$imageReferences];
              window.__GUIDENH_ACTION_LINKS__=${documentActionLinks(source)};
              window.__GUIDENH_BINDING_TARGETS__=$bindingTargets;
              window.__GUIDENH_BINDING_PREVIEWS__=$bindingPreviews;
            })();
        """.trimIndent()
    }

    private fun documentBindingContext(source: String): Pair<String, String> {
        val file = panel.virtualFile ?: return "{}" to "{}"
        val project = ProjectLocator.getInstance().guessProjectForFile(file) ?: return "{}" to "{}"
        val runtime = GuideNhRuntimeBridgeService.get()
        val index = GuideNhWorkspaceIndex.get(project)
        val locale = Regex("/guidenh/(?:guidenh/)?_([^/]+)/").find(file.path)?.groupValues?.get(1)?.lowercase()
        val targets = linkedMapOf<String, Any>()
        val previews = linkedMapOf<String, Any>()
        val pageBindings = mutableMapOf<VirtualFile, List<GuideNhReference>>()
        val bindings = GuideNhParser.parse(source).references.filter { it.kind == GuideNhReferenceKind.BINDING }
            .distinctBy { guideNhBindingIdentity(it.bindingType, it.value) }
        for (binding in bindings) {
            val identity = guideNhBindingIdentity(binding.bindingType, binding.value)
            val type = identity.substringBefore('\u0000')
            val id = identity.substringAfter('\u0000')
            val key = gson.toJson(listOf(type, id))
            val filters = mapOf("type" to type)
            runtime.prefetchPreview("bindings", id, filters = filters)
            runtime.previewCached("bindings", id, filters = filters)?.let { previews[key] = it }
            targets[key] = index.findBindingPages(type, id).filter { locale == null || it.locale == null || it.locale == locale }.flatMap { page ->
                val entries = pageBindings.getOrPut(page.file) {
                    val text = FileDocumentManager.getInstance().getCachedDocument(page.file)?.text
                        ?: runCatching { page.file.inputStream.use { it.reader(Charsets.UTF_8).readText() } }.getOrDefault("")
                    guideNhFrontmatterBindings(text)
                }
                entries.filter { guideNhBindingIdentity(it.bindingType, it.value) == identity }
                    .map { mapOf("path" to page.file.url, "anchor" to it.anchor.orEmpty().removePrefix("#"), "title" to page.file.name) }
            }.distinct()
        }
        return gson.toJson(targets) to gson.toJson(previews)
    }

    private fun documentSource(): String {
        val file = panel.virtualFile ?: return ""
        val cached = FileDocumentManager.getInstance().getCachedDocument(file)
        return cached?.immutableCharSequence?.toString()
            ?: runCatching { file.inputStream.use { it.reader(Charsets.UTF_8).readText() } }.getOrDefault("")
    }

    private fun documentActionLinks(source: String): String {
        if (!source.contains("&[")) return "[]"
        val pattern = Regex("""&\[((?:\\.|[^\]\\])*)]\(((?:\\.|[^)\\])*)\)""")
        val unescape = Regex("""\\([\[\]()\\])""")
        fun decode(value: String): String = unescape.replace(value) { match ->
            if (match.groupValues[1] == "\\") match.value else match.groupValues[1]
        }
        val links = mutableListOf<Map<String, String>>()
        fun collect(node: ASTNode) {
            if (node.type == MarkdownElementTypes.INLINE_LINK && node.startOffset > 0 && source[node.startOffset - 1] == '&') {
                pattern.matchAt(source, node.startOffset - 1)?.let { match ->
                    links += mapOf("label" to decode(match.groupValues[1]), "destination" to decode(match.groupValues[2]))
                }
            }
            node.children.forEach(::collect)
        }
        collect(MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source))
        return Gson().toJson(links)
    }

    /**
     * JCEF's Markdown renderer does not consistently retain the source destination as data-src.
     * Pass the source-order image references to the browser extension so it can still request the
     * GuideNH static resource endpoint after the built-in renderer has converted src.
     */
    private fun documentImageReferences(text: String): List<String> {
        val references = mutableListOf<String>()
        Regex("""!\[[^\]]*]\(\s*(?:<([^>]+)>|([^\s)]+)(?:\s+[^)]*)?)\s*\)""").findAll(text).forEach { match ->
            (match.groupValues[1].ifBlank { match.groupValues[2] }).takeIf(String::isNotBlank)?.let(references::add)
        }
        Regex("""<FloatingImage\b[^>]*\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            (match.groupValues[1].ifBlank { match.groupValues[2] }).takeIf(String::isNotBlank)?.let(references::add)
        }
        return references
    }

    private fun imageResource(resourceName: String): ResourceProvider.Resource? {
        if (!resourceName.startsWith(imageResourcePrefix)) return null
        // Resource names are part of the static-server URL path. Do not pass a logical
        // GuideNH reference such as ../assets/... through that path: HTTP decoding can
        // normalize it before this provider receives the request.
        val reference = runCatching {
            String(Base64.getUrlDecoder().decode(resourceName.removePrefix(imageResourcePrefix)), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val target = resolveGuideNhImage(reference) ?: return null
        return ResourceProvider.loadExternalResource(target, imageMimeType(target.extension.orEmpty()))
    }

    private fun imageMimeType(extension: String): String = when (extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    /** Matches GuideNH's IdUtils.resolveLink followed by MutableGuide.loadAsset fallback. */
    private fun resolveGuideNhImage(reference: String): VirtualFile? {
        val document = panel.virtualFile ?: return null
        val path = document.path.replace('\\', '/')
        val location = Regex("^(.*?/assets/)([^/]+)(/guidenh/)(?:guidenh/)?_([a-z]{2}_[a-z]{2})/(.+\\.md)$", RegexOption.IGNORE_CASE)
            .find(path) ?: return document.parent?.findFileByRelativePath(reference)
        val recoveredLogicalPath = guideLogicalPathFromFileUri(reference)
        val logicalReference = recoveredLogicalPath ?: reference
        if (logicalReference.isBlank() || logicalReference.startsWith('#') ||
            (recoveredLogicalPath == null && Regex("^(?:[a-z][a-z0-9+.-]*:|//)", RegexOption.IGNORE_CASE).containsMatchIn(logicalReference))) {
            return null
        }

        val assetsRoot = location.groupValues[1]
        val documentNamespace = location.groupValues[2]
        val guideFolder = location.groupValues[3]
        val locale = location.groupValues[4]
        val pagePath = location.groupValues[5]
        val explicit = Regex("^([A-Za-z0-9_.-]+):/?(.+)$").find(logicalReference)
        val namespace = explicit?.groupValues?.get(1) ?: documentNamespace
        val resourcePath = explicit?.groupValues?.get(2) ?: logicalReference
        val logicalPath = if (recoveredLogicalPath != null || logicalReference.startsWith("/") || explicit != null) {
            normalizeGuidePath(resourcePath)
        } else {
            normalizeGuidePath("${pagePath.substringBeforeLast('/', "")}/$resourcePath")
        }
        if (logicalPath.isBlank()) return null

        val guideRoot = "$assetsRoot$namespace$guideFolder"
        return listOf("${guideRoot}_$locale/$logicalPath", "$guideRoot$logicalPath")
            .asSequence()
            .mapNotNull { candidate -> LocalFileSystem.getInstance().findFileByPath(candidate) }
            .firstOrNull()
    }

    private fun normalizeGuidePath(value: String): String {
        val result = mutableListOf<String>()
        value.replace('\\', '/').split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (result.isNotEmpty()) result.removeAt(result.lastIndex)
                else -> result += segment
            }
        }
        return result.joinToString("/")
    }

    /** Recovers the logical resource path if IntelliJ resolved Markdown relative to _<locale>. */
    private fun guideLogicalPathFromFileUri(reference: String): String? {
        if (!reference.startsWith("file:", ignoreCase = true)) return null
        val physicalPath = runCatching { Paths.get(URI(reference)).toString().replace('\\', '/') }.getOrNull()
            ?: return null
        val marker = physicalPath.indexOf("/guidenh/", ignoreCase = true)
        if (marker < 0) return null
        var logicalPath = physicalPath.substring(marker + "/guidenh/".length).removePrefix("guidenh/")
        val localized = Regex("^_[^/]+/(.+)$").matchEntire(logicalPath)
        if (localized != null) logicalPath = localized.groupValues[1]
        return logicalPath
    }

    private fun resource(path: String, mime: String): ResourceProvider.Resource {
        val bytes = GuideNhMarkdownPreviewExtension::class.java.getResourceAsStream(path)?.use { it.readBytes() } ?: ByteArray(0)
        return ResourceProvider.Resource(bytes, mime)
    }
}

private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
private fun escapeJavaScript(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "")
