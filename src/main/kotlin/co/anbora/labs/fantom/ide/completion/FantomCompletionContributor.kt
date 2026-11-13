package co.anbora.labs.fantom.ide.completion

import co.anbora.labs.fantom.ide.imports.FantomUsingInserter
import co.anbora.labs.fantom.ide.presentation.FantomPresentation
import co.anbora.labs.fantom.lang.FantomLanguage
import co.anbora.labs.fantom.lang.core.psi.FantomCall
import co.anbora.labs.fantom.lang.core.psi.FantomCatchDef
import co.anbora.labs.fantom.lang.core.psi.FantomClosureParam
import co.anbora.labs.fantom.lang.core.psi.FantomCtorDef
import co.anbora.labs.fantom.lang.core.psi.FantomDotCall
import co.anbora.labs.fantom.lang.core.psi.FantomDynCallStm
import co.anbora.labs.fantom.lang.core.psi.FantomEnumValDef
import co.anbora.labs.fantom.lang.core.psi.FantomFacetItems
import co.anbora.labs.fantom.lang.core.psi.FantomFacetVal
import co.anbora.labs.fantom.lang.core.psi.FantomField
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomHighOrderBlock
import co.anbora.labs.fantom.lang.core.psi.FantomId
import co.anbora.labs.fantom.lang.core.psi.FantomLiteral
import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomParam
import co.anbora.labs.fantom.lang.core.psi.FantomSafeDotCallStm
import co.anbora.labs.fantom.lang.core.psi.FantomSafeDynCallStm
import co.anbora.labs.fantom.lang.core.psi.FantomSimpleType
import co.anbora.labs.fantom.lang.core.psi.FantomSlotDefs
import co.anbora.labs.fantom.lang.core.psi.FantomSlotExpr
import co.anbora.labs.fantom.lang.core.psi.FantomStatementBlock
import co.anbora.labs.fantom.lang.core.psi.FantomStaticCall
import co.anbora.labs.fantom.lang.core.psi.FantomStmt
import co.anbora.labs.fantom.lang.core.psi.FantomTermChain
import co.anbora.labs.fantom.lang.core.psi.FantomTermExpr
import co.anbora.labs.fantom.lang.core.psi.FantomType
import co.anbora.labs.fantom.lang.core.psi.FantomTypes
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.index.FantomDeclarationKind
import co.anbora.labs.fantom.lang.resolve.FantomScope
import co.anbora.labs.fantom.lang.resolve.FantomSlotLookup
import co.anbora.labs.fantom.lang.resolve.FantomUsingScope
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.intellij.util.indexing.FileBasedIndex

/**
 * Basic completion for Fantom.
 *
 * Positions are told apart by where the dummy identifier lands in the tree:
 * - after `.`, `?.`, `->`: slots of the receiver's inferred type chain (`this.` and
 *   `super.` use the enclosing type; `Type.` uses that type); no keywords;
 * - a bare name: locals and params in scope, slots of the enclosing type chain,
 *   then type names from the index and the keywords that fit the spot;
 * - a type position (`Foo` in a declaration, `is`, `as`, cast): type names only;
 * - a name being declared (`Str <caret>`): nothing;
 * - outside an expression (file level, type body): keywords, plus types in a type body.
 * - a facet value name (`@Foo { <caret> = 1 }`): the facet type's instance fields.
 * Nothing is offered for a receiver whose type is unknown; a wrong list is worse than none.
 * The empty lookup then says so ([unknownReceiverText]) instead of "No suggestions".
 *
 * Order, through [PrioritizedLookupElement] (the platform still lifts exact prefix
 * matches): locals and params nearest first, own-type slots in source order,
 * inherited slots, `Obj` slots, types, keywords. After `Type.`: static slots and ctors
 * first, instance slots after, `Obj` last. Slots with `private`, `protected` or
 * `internal` sort below public ones when the receiver is not `this`.
 */
class FantomCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            psiElement(FantomTypes.IDENTIFIER).withLanguage(FantomLanguage),
            FantomCompletionProvider(),
        )
    }

    override fun handleEmptyLookup(parameters: CompletionParameters, editor: Editor): String? =
        unknownReceiverText(parameters.position) ?: super.handleEmptyLookup(parameters, editor)

    /** What stands in front of the dot. */
    private enum class Receiver { BARE, THIS, INSTANCE, TYPE }

    /** Where a slot comes from in the receiver's type chain. */
    private enum class Tier(val base: Double) { OWN(OWN_SLOT), INHERITED(INHERITED_SLOT), OBJ(OBJ_SLOT) }

    private class FantomCompletionProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            val position = parameters.position
            val project = position.project
            val scope = GlobalSearchScope.allScope(project)
            val seen = HashSet<String>()
            fun add(e: LookupElement) { if (seen.add(e.lookupString)) result.addElement(e) }

            val id = position.parent as? FantomId
            if (id == null) {
                addOutsideExpression(position, project, scope, ::add)
                return
            }

            when (val owner = id.parent) {
                is FantomSimpleType -> when {
                    isStatementStartParsedAsType(owner) -> addBare(id, project, scope, ::add)
                    else -> {
                        addTypes(id.containingFile, project, scope, ::add)
                        if (isSlotStartParsedAsType(owner)) addKeywords(FantomKeywords.TYPE_BODY, ::add)
                    }
                }
                is FantomFacetVal -> addFacetFields(owner, project, scope, ::add)
                is FantomField, is FantomCall -> {
                    val slotExpr = owner.parent as? FantomSlotExpr
                    val chainOp = slotExpr?.parent
                    when {
                        chainOp is FantomStaticCall -> {
                            val typeName = chainOp.type.simpleType?.idList?.lastOrNull()?.text ?: return
                            val type = FantomTypeInference.typeDefinitions(typeName, project).firstOrNull() ?: return
                            addReceiverSlots(type, Receiver.TYPE, id, project, scope, ::add)
                        }
                        chainOp is FantomDotCall || chainOp is FantomSafeDotCallStm ||
                            chainOp is FantomDynCallStm || chainOp is FantomSafeDynCallStm -> {
                            val type = receiverTypeDef(chainOp, project) ?: return
                            val kind = if (isThisOrSuperReceiver(chainOp)) Receiver.THIS else Receiver.INSTANCE
                            addReceiverSlots(type, kind, id, project, scope, ::add)
                        }
                        else -> addBare(id, project, scope, ::add)
                    }
                }
                else -> if (!isDeclaredName(owner)) addBare(id, project, scope, ::add)
            }
        }

        /** `@Foo { <caret> }`: the non-static fields on Foo's chain, own fields first. */
        private fun addFacetFields(value: FantomFacetVal, project: Project, scope: GlobalSearchScope, add: (LookupElement) -> Unit) {
            val typeName = (value.parent?.parent as? FantomFacetItems)?.simpleType?.idList?.lastOrNull()?.text ?: return
            val type = FantomTypeInference.typeDefinitions(typeName, project).firstOrNull() ?: return
            var index = 0
            for ((slot, tier) in rankedSlots(type, project, scope)) {
                if (slot !is FantomFieldDef || "static" in FantomPresentation.modifiers(slot)) continue
                add(withPriority(slotElement(slot), tier.base - index * STEP))
                index++
            }
        }

        // ---- positions -----------------------------------------------------------------

        /** The type declaration whose slots a `.`-style chain op reaches, or null when unknown. */
        private fun receiverTypeDef(chainOp: PsiElement, project: Project): FantomNamedElement? {
            if (isThisOrSuperReceiver(chainOp)) return FantomScope.enclosingType(chainOp)
            val recv = FantomTypeInference.receiverType(chainOp) ?: return null
            return FantomTypeInference.typeDefinitions(recv.slotOwnerName, project).firstOrNull()
        }

        private fun isThisOrSuperReceiver(chainOp: PsiElement): Boolean {
            val step = chainOp.parent as? FantomTermChain ?: return false
            val term = step.parent as? FantomTermExpr ?: return false
            if (term.termChainList.firstOrNull() != step) return false
            val lit: FantomLiteral = term.termBase?.literal ?: return false
            return lit.node.findChildByType(FantomTypes.THIS) != null || lit.node.findChildByType(FantomTypes.SUPER) != null
        }

        /** `Str <caret>`, `Void f<caret>()`, `x<caret> := 1`: the user names something new. */
        private fun isDeclaredName(owner: PsiElement): Boolean =
            owner is FantomFieldDef || owner is FantomMethodDef || owner is FantomCtorDef || owner is FantomLocalDef ||
                owner is FantomParam || owner is FantomClosureParam || owner is FantomCatchDef || owner is FantomEnumValDef ||
                FantomScope.isTypeDef(owner)

        /**
         * `<caret>` on its own line followed by `y := 2` parses as `Type y := 2`, so the dummy
         * lands in a local's type. When a line break separates the two, the user is at the
         * start of a statement, not writing a type.
         */
        private fun isStatementStartParsedAsType(simpleType: FantomSimpleType): Boolean {
            val type = simpleType.parent as? FantomType ?: return false
            val local = type.parent as? FantomLocalDef ?: return false
            if (local.type != type) return false
            val nameId = local.id ?: return false
            val gap = local.containingFile.text.subSequence(type.textRange.endOffset, nameId.textRange.startOffset)
            return gap.contains('\n')
        }

        /**
         * `<caret>` on its own line inside a type body followed by `Void f() {}` parses as a
         * slot whose return or field type is the dummy. With no flag before it, the user
         * may be typing a flag just as well as a type.
         */
        private fun isSlotStartParsedAsType(simpleType: FantomSimpleType): Boolean {
            val type = simpleType.parent as? FantomType ?: return false
            val slot = type.parent ?: return false
            val declared = when (slot) {
                is FantomFieldDef -> slot.type
                is FantomMethodDef -> slot.type
                else -> return false
            }
            return declared == type && slot.textRange.startOffset == type.textRange.startOffset
        }

        /** True when [element] is the first token of the statement that holds it. */
        private fun isStatementStart(element: PsiElement): Boolean {
            val stmt = PsiTreeUtil.getParentOfType(element, FantomStmt::class.java) ?: return false
            return stmt.textRange.startOffset == element.textRange.startOffset
        }

        // ---- lists ----------------------------------------------------------------------

        /** The dummy sits in no [FantomId]: file level, a type body between slots, or a broken block. */
        private fun addOutsideExpression(position: PsiElement, project: Project, scope: GlobalSearchScope, add: (LookupElement) -> Unit) {
            var p: PsiElement? = position.parent
            while (p is PsiErrorElement) p = p.parent
            when {
                p == null || p is PsiFile -> addKeywords(FantomKeywords.FILE, add)
                p is FantomSlotDefs || p is FantomHighOrderBlock -> {
                    addTypes(position.containingFile, project, scope, add)
                    addKeywords(FantomKeywords.TYPE_BODY, add)
                }
                PsiTreeUtil.getParentOfType(position, FantomStatementBlock::class.java) != null -> addBare(position, project, scope, add)
            }
        }

        private fun addBare(at: PsiElement, project: Project, scope: GlobalSearchScope, add: (LookupElement) -> Unit) {
            val locals = FantomScope.localsVisibleAt(at).sortedByDescending { it.textRange.startOffset }
            locals.forEachIndexed { i, local -> add(withPriority(localElement(local), LOCAL - i * STEP)) }
            FantomScope.enclosingType(at)?.let { type -> addReceiverSlots(type, Receiver.BARE, at, project, scope, add) }
            addTypes(at.containingFile, project, scope, add)
            if (isStatementStart(at)) addKeywords(FantomKeywords.STATEMENT, add)
            addKeywords(FantomKeywords.VALUES, add)
        }

        /**
         * Slots of [type]'s chain as seen through [kind]. Ctors show only after `Type.`
         * and as bare names inside their own type; `private` slots of another type are
         * hidden; `protected`/`internal` ones sort low unless the receiver is `this`.
         */
        private fun addReceiverSlots(
            type: FantomNamedElement, kind: Receiver, at: PsiElement,
            project: Project, scope: GlobalSearchScope, add: (LookupElement) -> Unit,
        ) {
            val hereName = FantomScope.enclosingType(at)?.name
            var index = 0
            for ((slot, tier) in rankedSlots(type, project, scope)) {
                val mods = FantomPresentation.modifiers(slot)
                val isCtor = slot is FantomCtorDef
                val foreign = hereName == null || FantomScope.enclosingType(slot)?.name != hereName
                if (isCtor && tier != Tier.OWN) continue
                if (isCtor && kind != Receiver.TYPE && kind != Receiver.BARE) continue
                if ("private" in mods && foreign) continue

                var priority = tier.base
                if (kind == Receiver.TYPE && tier != Tier.OBJ) {
                    val static = isCtor || slot is FantomEnumValDef || "static" in mods
                    priority = if (static) OWN_SLOT else INHERITED_SLOT
                }
                if (kind == Receiver.INSTANCE || kind == Receiver.TYPE) {
                    if ("protected" in mods || "internal" in mods || "private" in mods) priority -= RESTRICTED
                }
                add(withPriority(slotElement(slot), priority - index * STEP))
                index++
            }
        }

        /** Every slot on [start]'s chain, nearest declaration first, with where it came from. */
        private fun rankedSlots(start: FantomNamedElement, project: Project, scope: GlobalSearchScope): List<Pair<FantomNamedElement, Tier>> {
            val out = ArrayList<Pair<FantomNamedElement, Tier>>()
            val seen = HashSet<String>()
            FantomSlotLookup.typeChain(start, project, scope).forEachIndexed { i, type ->
                val tier = when {
                    i == 0 -> Tier.OWN
                    type.name == "Obj" -> Tier.OBJ
                    else -> Tier.INHERITED
                }
                for (slot in FantomScope.ownSlots(type)) {
                    val n = slot.name ?: continue
                    if (seen.add(n)) out += slot to tier
                }
            }
            return out
        }

        /**
         * Type names from the index. A type no `using` line of [file] makes visible shows
         * its pod as grey tail text, `Actor (concurrent)`, and inserting it adds
         * `using <pod>::<Name>`. A visible type gets neither. When the file's `build.fan` has a
         * literal `depends`, a type of a pod outside it ranks lower and its tail adds
         * `(not in depends)`.
         */
        private fun addTypes(file: PsiFile, project: Project, scope: GlobalSearchScope, add: (LookupElement) -> Unit) {
            val usings = FantomUsingScope.usingsOf(file.originalFile)
            val index = FileBasedIndex.getInstance()
            for (name in FantomDeclarationIndex.allNames(project, scope)) {
                if (name.isEmpty() || !name[0].isUpperCase()) continue
                var kind: FantomDeclarationKind? = null
                var visible = false
                val pods = java.util.TreeSet<String>()
                index.processValues(FantomDeclarationIndex.NAME, name, null, { vf, entries ->
                    val typeKind = entries.firstOrNull { it.kind.isType }?.kind
                    if (typeKind != null) {
                        if (kind == null) kind = typeKind
                        if (!visible) {
                            val pod = FantomUsingScope.podOf(vf, project)
                            if (usings.isVisible(name, pod)) visible = true else pods += pod!!
                        }
                    }
                    true
                }, scope)
                val icon = when (kind ?: continue) {
                    FantomDeclarationKind.MIXIN -> AllIcons.Nodes.Interface
                    FantomDeclarationKind.ENUM -> AllIcons.Nodes.Enum
                    FantomDeclarationKind.FACET -> AllIcons.Nodes.Annotationtype
                    else -> AllIcons.Nodes.Class
                }
                var element = LookupElementBuilder.create(name).withIcon(icon)
                var priority = TYPE
                if (!visible && pods.isNotEmpty()) {
                    // A pod of the build.fan `depends` first; others only when no depended pod has the name.
                    val pod = pods.firstOrNull { usings.isPodAvailable(it) } ?: pods.first()
                    val outside = usings.isOutsideDepends(pod)
                    element = element.withTailText(if (outside) " ($pod) (not in depends)" else " ($pod)", true)
                        .withInsertHandler(FantomUsingInserter.AddUsingInsertHandler(pod, name))
                    if (outside) priority = TYPE_NOT_IN_DEPENDS
                }
                add(withPriority(element, priority))
            }
        }

        private fun addKeywords(words: List<String>, add: (LookupElement) -> Unit) {
            for (kw in words) add(withPriority(LookupElementBuilder.create(kw).bold(), KEYWORD))
        }

        // ---- elements -------------------------------------------------------------------

        private fun withPriority(e: LookupElement, priority: Double): LookupElement =
            PrioritizedLookupElement.withPriority(e, priority)

        private fun localElement(decl: FantomNamedElement): LookupElement {
            val type = FantomTypeInference.typeOfDeclaration(decl)?.presentableText ?: ""
            val icon = if (decl is FantomParam) AllIcons.Nodes.Parameter else AllIcons.Nodes.Variable
            return LookupElementBuilder.create(decl, decl.name ?: "").withIcon(icon).withTypeText(type, true)
        }

        /**
         * `name` with the slot type on the right (`Str`, `Int[]`); methods and ctors add a
         * `(Int x, Str y)` tail. A ctor's type text is its type. A method with params
         * inserts `()` with the caret inside; one without inserts the name only, as Fantom
         * calls it without parens.
         */
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
    }

    companion object {
        /**
         * The empty-lookup text after `x.` when the type of `x` is unknown, or null when
         * [position] is not such a spot (or the type is known).
         */
        fun unknownReceiverText(position: PsiElement): String? {
            val id = position.parent as? FantomId ?: return null
            val slotExpr = (id.parent as? FantomField ?: id.parent as? FantomCall)?.parent as? FantomSlotExpr ?: return null
            val chainOp = slotExpr.parent
            if (chainOp !is FantomDotCall && chainOp !is FantomSafeDotCallStm) return null
            val step = chainOp.parent as? FantomTermChain ?: return null
            val term = step.parent as? FantomTermExpr ?: return null
            if (term.termChainList.firstOrNull() == step) {
                val lit = term.termBase?.literal
                if (lit != null && (lit.node.findChildByType(FantomTypes.THIS) != null || lit.node.findChildByType(FantomTypes.SUPER) != null)) return null
            }
            if (FantomTypeInference.receiverType(chainOp) != null) return null
            val start = term.textRange.startOffset
            val receiver = term.containingFile.text.substring(start, chainOp.textRange.startOffset).trim()
                .let { if (it.length > 30) it.take(27) + "..." else it }
            return "Type of '$receiver' unknown: declare it (Str x := ...) to see its slots"
        }

        // Priority ladder. Higher comes first; items in one group keep their order through STEP.
        const val LOCAL = 100.0
        const val OWN_SLOT = 80.0
        const val INHERITED_SLOT = 70.0
        const val OBJ_SLOT = 60.0
        const val TYPE = 40.0
        /** A type whose pod is not in the `depends` of the file's `build.fan`. */
        const val TYPE_NOT_IN_DEPENDS = 35.0
        const val KEYWORD = 30.0
        const val RESTRICTED = 5.0
        const val STEP = 0.001
    }
}
