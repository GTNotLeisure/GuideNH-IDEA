package com.hfstudio.guidenh

import com.intellij.openapi.editor.CustomFileDropHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.command.WriteCommandAction
import java.awt.datatransfer.Transferable
import com.google.gson.JsonParser

/** Handles plain-text and GuideNH JSON item drops in the native editor. */
class GuideNhItemDropHandler : CustomFileDropHandler() {
    override fun canHandle(transferable: Transferable, editor: Editor?): Boolean {
        if (editor == null) return false
        val file = editor.virtualFile ?: return false
        return GuideNhFileUtil.isGuideNhDocument(file) && content(transferable) != null
    }

    override fun handleDrop(transferable: Transferable, editor: Editor?, project: Project): Boolean {
        if (editor == null) return false
        val (id, capability) = content(transferable) ?: return false
        val context = GuideNhItemStackContexts.findAt(editor.document.charsSequence, editor.caretModel.offset)
        if (context != null && context.capability != capability) return false
        WriteCommandAction.runWriteCommandAction(project) {
            val document = editor.document
            val offset = editor.caretModel.offset.coerceIn(0, document.textLength)
            if (context == null) document.insertString(offset, if (capability == "fluids") "<FluidImage id=\"${id.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")}\" />" else id)
            else document.replaceString(context.valueStart, context.valueEndExclusive,
                id.replace("&", "&amp;").replace("\"", "&quot;").replace("'", "&apos;").replace("<", "&lt;").replace(">", "&gt;"))
        }
        return true
    }

    private fun content(transferable: Transferable): Pair<String, String>? {
        val flavors = transferable.transferDataFlavors
        val custom = flavors.firstOrNull { it.mimeType.startsWith("application/vnd.guidenh.itemstack+json") }
            ?.let { runCatching { transferable.getTransferData(it).toString() }.getOrNull() }
        if (custom != null) return runCatching {
            val objectValue = JsonParser.parseString(custom).asJsonObject
            val id = objectValue.get("id").asString.takeIf { it.isNotBlank() && it.length <= 512 } ?: return@runCatching null
            val capability = objectValue.get("capability")?.asString ?: "items"
            if (capability !in setOf("items", "fluids")) null else id to capability
        }.getOrNull()
        return flavors.firstOrNull { it.isFlavorTextType }
            ?.let { runCatching { transferable.getTransferData(it).toString().trim() }.getOrNull() }
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]+:[A-Za-z0-9_./-]+(?::\\d+)?")) }?.let { it to "items" }
    }
}
