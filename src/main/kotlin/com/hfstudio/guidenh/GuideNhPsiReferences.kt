package com.hfstudio.guidenh

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.VirtualFile

/** Shared PSI reference construction used by navigation and IntelliJ's references-search EP. */
object GuideNhPsiReferences {
    fun create(file: PsiFile, element: PsiElement = file, includeDeclarations: Boolean = true): Array<PsiReference> {
        val absoluteStart = element.textRange.startOffset
        val elementEnd = element.textRange.endOffset
        return GuideNhParser.parse(file.text).references
            .filter { (includeDeclarations || !it.declaration) && it.range.startOffset >= absoluteStart && it.range.endOffset <= elementEnd }
            .map { reference ->
                object : PsiPolyVariantReferenceBase<PsiElement>(
                    element,
                    TextRange(reference.range.startOffset - absoluteStart, reference.range.endOffset - absoluteStart),
                    true
                ) {
                    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> = resolveElements(file, reference)
                        .map { PsiElementResolveResult(it) }.toTypedArray()

                    override fun getVariants(): Array<Any> = when (reference.kind) {
                        GuideNhReferenceKind.PAGE -> GuideNhWorkspaceIndex.get(file.project)
                            .queryPages(reference.value, file.virtualFile).map { it.relativePath }
                        GuideNhReferenceKind.RESOURCE -> GuideNhWorkspaceIndex.get(file.project)
                            .queryResources(reference.value, file.virtualFile).map { it.relativePath }
                        GuideNhReferenceKind.BINDING -> GuideNhWorkspaceIndex.get(file.project).findBindingPages(reference.bindingType, reference.value).map { it.pageId }
                        GuideNhReferenceKind.FLUID -> GuideNhWorkspaceIndex.get(file.project).findFluidPages(reference.value).map { it.pageId }
                        GuideNhReferenceKind.ITEM -> GuideNhWorkspaceIndex.get(file.project)
                            .findItemPages(reference.value).map { it.pageId }
                        GuideNhReferenceKind.ORE -> GuideNhWorkspaceIndex.get(file.project)
                            .findOrePages(reference.value).map { it.pageId }
                    }.toTypedArray()
                }
            }.toTypedArray()
    }

    fun resolveElements(source: PsiFile, reference: GuideNhReference): List<PsiElement> {
        if (reference.kind != GuideNhReferenceKind.BINDING) {
            val element = resolveFile(source, reference)?.let { PsiManager.getInstance(source.project).findFile(it) }
                ?: GuideNhRuntimeSemanticIndex.get(source.project).resolve(reference)
            return listOfNotNull(element)
        }
        val identity = guideNhBindingIdentity(reference.bindingType, reference.value)
        val results = GuideNhWorkspaceIndex.get(source.project).findBindingPages(reference.bindingType, reference.value).flatMap { page ->
            val target = PsiManager.getInstance(source.project).findFile(page.file) ?: return@flatMap emptyList()
            guideNhFrontmatterBindings(target.text).filter { guideNhBindingIdentity(it.bindingType, it.value) == identity }.map { binding ->
                val offset = guideNhBindingAnchorOffset(target.text, binding.anchor) ?: binding.range.startOffset
                target.findElementAt(offset) ?: target
            }
        }
        return results.ifEmpty { listOfNotNull(GuideNhRuntimeSemanticIndex.get(source.project).resolve(reference)) }
    }

    fun resolveFile(source: PsiFile, reference: GuideNhReference): VirtualFile? {
        val index = GuideNhWorkspaceIndex.get(source.project)
        return when (reference.kind) {
            GuideNhReferenceKind.PAGE -> index.findPage(reference.value, source.virtualFile)?.file
            GuideNhReferenceKind.RESOURCE -> index.findResource(reference.value, source.virtualFile)?.file
            GuideNhReferenceKind.BINDING -> index.findBindingPages(reference.bindingType, reference.value).firstOrNull()?.file
            GuideNhReferenceKind.FLUID -> index.findFluidPages(reference.value).firstOrNull()?.file
            GuideNhReferenceKind.ITEM -> index.findItemPages(reference.value).firstOrNull()?.file
            GuideNhReferenceKind.ORE -> index.findOrePages(reference.value).firstOrNull()?.file
        }
    }
}
