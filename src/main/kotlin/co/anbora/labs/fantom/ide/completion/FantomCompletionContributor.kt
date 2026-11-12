package co.anbora.labs.fantom.ide.completion

import co.anbora.labs.fantom.ide.presentation.FantomPresentation
import co.anbora.labs.fantom.lang.FantomLanguage
import co.anbora.labs.fantom.lang.core.psi.FantomCall
import co.anbora.labs.fantom.lang.core.psi.FantomCtorDef
import co.anbora.labs.fantom.lang.core.psi.FantomDotCall
import co.anbora.labs.fantom.lang.core.psi.FantomDynCallStm
import co.anbora.labs.fantom.lang.core.psi.FantomField
import co.anbora.labs.fantom.lang.core.psi.FantomId
import co.anbora.labs.fantom.lang.core.psi.FantomLiteral
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomParam
import co.anbora.labs.fantom.lang.core.psi.FantomSafeDotCallStm
import co.anbora.labs.fantom.lang.core.psi.FantomSafeDynCallStm
import co.anbora.labs.fantom.lang.core.psi.FantomSimpleType
import co.anbora.labs.fantom.lang.core.psi.FantomSlotExpr
import co.anbora.labs.fantom.lang.core.psi.FantomStaticCall
import co.anbora.labs.fantom.lang.core.psi.FantomTermChain
import co.anbora.labs.fantom.lang.core.psi.FantomTermExpr
import co.anbora.labs.fantom.lang.core.psi.FantomTypes
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.index.FantomDeclarationKind
import co.anbora.labs.fantom.lang.resolve.FantomScope
import co.anbora.labs.fantom.lang.resolve.FantomSlotLookup
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

/**
 * Basic completion for Fantom.
 *
 * Three positions are told apart by where the dummy identifier lands in the tree:
 * - after `.`, `?.`, `->`: slots of the receiver's inferred type chain (`this.` and
 *   `super.` use the enclosing type; `Type.` uses that type);
 * - a bare name: locals and params in scope, slots of the enclosing type chain,
 *   then type names from the index and keywords;
 * - a type position (`Foo` in a declaration, `is`, `as`, cast): type names only.
 * Nothing is offered for a receiver whose type is unknown; a wrong list is worse than none.
 */
class FantomCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            psiElement(FantomTypes.IDENTIFIER).withLanguage(FantomLanguage),
            FantomCompletionProvider(),
        )
    }

    private class FantomCompletionProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            val id = parameters.position.parent as? FantomId ?: return
            val project = id.project
            val scope = GlobalSearchScope.allScope(project)
            val seen = HashSet<String>()
            fun add(e: LookupElement) { if (seen.add(e.lookupString)) result.addElement(e) }

            when (val owner = id.parent) {
                is FantomSimpleType -> {
                    addTypes(project, scope, ::add)
                }
                is FantomField, is FantomCall -> {
                    val slotExpr = owner.parent as? FantomSlotExpr
                    val chainOp = slotExpr?.parent
                    when {
                        chainOp is FantomStaticCall -> {
                            chainOp.type.simpleType?.idList?.lastOrNull()?.text?.let { typeName ->
                                FantomSlotLookup.allSlotsOfTypeName(typeName, project, scope).forEach { add(slotElement(it)) }
                            }
                        }
                        chainOp is FantomDotCall || chainOp is FantomSafeDotCallStm ||
                            chainOp is FantomDynCallStm || chainOp is FantomSafeDynCallStm -> {
                            receiverSlots(chainOp, project, scope).forEach { add(slotElement(it)) }
                        }
                        else -> addBare(id, project, scope, ::add)
                    }
                }
                else -> addBare(id, project, scope, ::add)
            }
        }

        private fun receiverSlots(chainOp: PsiElement, project: com.intellij.openapi.project.Project, scope: GlobalSearchScope): List<FantomNamedElement> {
            if (isThisOrSuperReceiver(chainOp)) {
                val type = FantomScope.enclosingType(chainOp) ?: return emptyList()
                return FantomSlotLookup.allSlots(type, project, scope)
            }
            val recv = FantomTypeInference.receiverType(chainOp) ?: return emptyList()
            return FantomSlotLookup.allSlotsOfTypeName(recv.slotOwnerName, project, scope)
        }

        private fun isThisOrSuperReceiver(chainOp: PsiElement): Boolean {
            val step = chainOp.parent as? FantomTermChain ?: return false
            val term = step.parent as? FantomTermExpr ?: return false
            if (term.termChainList.firstOrNull() != step) return false
            val lit: FantomLiteral = term.termBase?.literal ?: return false
            return lit.node.findChildByType(FantomTypes.THIS) != null || lit.node.findChildByType(FantomTypes.SUPER) != null
        }

        private fun addBare(id: FantomId, project: com.intellij.openapi.project.Project, scope: GlobalSearchScope, add: (LookupElement) -> Unit) {
            for (local in FantomScope.localsVisibleAt(id)) add(localElement(local))
            FantomScope.enclosingType(id)?.let { type ->
                for (slot in FantomSlotLookup.allSlots(type, project, scope)) add(slotElement(slot))
            }
            addTypes(project, scope, add)
            for (kw in KEYWORDS) add(LookupElementBuilder.create(kw).bold())
        }

        private fun addTypes(project: com.intellij.openapi.project.Project, scope: GlobalSearchScope, add: (LookupElement) -> Unit) {
            for (name in FantomDeclarationIndex.allNames(project, scope)) {
                if (name.isEmpty() || !name[0].isUpperCase()) continue
                val kinds = FantomDeclarationIndex.entries(name, project, scope)
                val kind = kinds.firstOrNull { it.kind.isType }?.kind ?: continue
                val icon = when (kind) {
                    FantomDeclarationKind.MIXIN -> AllIcons.Nodes.Interface
                    FantomDeclarationKind.ENUM -> AllIcons.Nodes.Enum
                    FantomDeclarationKind.FACET -> AllIcons.Nodes.Annotationtype
                    else -> AllIcons.Nodes.Class
                }
                add(LookupElementBuilder.create(name).withIcon(icon))
            }
        }

        private fun localElement(decl: FantomNamedElement): LookupElement {
            val type = FantomTypeInference.typeOfDeclaration(decl)?.presentableText ?: ""
            val icon = if (decl is FantomParam) AllIcons.Nodes.Parameter else AllIcons.Nodes.Variable
            return LookupElementBuilder.create(decl, decl.name ?: "").withIcon(icon).withTypeText(type, true)
        }

        private fun slotElement(slot: FantomNamedElement): LookupElement {
            val name = slot.name ?: ""
            val type = FantomTypeInference.typeOfDeclaration(slot)?.presentableText ?: ""
            var b = LookupElementBuilder.create(slot, name)
                .withIcon(FantomPresentation.icon(slot))
                .withTypeText(type, true)
            if (slot is FantomMethodDef || slot is FantomCtorDef) {
                val params = PsiTreeUtil.findChildrenOfType(slot, FantomParam::class.java)
                b = b.withTailText("(" + params.joinToString(", ") { p -> (p.type?.text ?: "") + " " + (p.name ?: "") } + ")", true)
                if (params.isNotEmpty()) b = b.withInsertHandler(ParensInsertHandler)
            }
            return b
        }

        private object ParensInsertHandler : InsertHandler<LookupElement> {
            override fun handleInsert(context: InsertionContext, item: LookupElement) {
                val doc = context.document
                val offset = context.tailOffset
                if (offset < doc.textLength && doc.charsSequence[offset] == '(') return
                doc.insertString(offset, "()")
                context.editor.caretModel.moveToOffset(offset + 1)
            }
        }

        companion object {
            val KEYWORDS = listOf(
                "abstract", "as", "assert", "break", "case", "catch", "class", "const", "continue", "default", "do",
                "else", "enum", "facet", "false", "final", "finally", "for", "foreach", "if", "internal", "is", "isnot",
                "it", "mixin", "native", "new", "null", "once", "override", "private", "protected", "public", "readonly",
                "return", "static", "super", "switch", "this", "throw", "true", "try", "using", "virtual", "void",
                "volatile", "while",
            )
        }
    }
}
