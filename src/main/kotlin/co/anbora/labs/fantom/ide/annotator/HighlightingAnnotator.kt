package co.anbora.labs.fantom.ide.annotator

import co.anbora.labs.fantom.ide.color.FantomColors
import co.anbora.labs.fantom.lang.core.psi.FantomCatchDef
import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomClosureParam
import co.anbora.labs.fantom.lang.core.psi.FantomCtorDef
import co.anbora.labs.fantom.lang.core.psi.FantomDotCall
import co.anbora.labs.fantom.lang.core.psi.FantomEnumDef
import co.anbora.labs.fantom.lang.core.psi.FantomEnumValDef
import co.anbora.labs.fantom.lang.core.psi.FantomFacetDef
import co.anbora.labs.fantom.lang.core.psi.FantomFacetItems
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomId
import co.anbora.labs.fantom.lang.core.psi.FantomList
import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomMixinDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomParam
import co.anbora.labs.fantom.lang.core.psi.FantomSimple
import co.anbora.labs.fantom.lang.core.psi.FantomSimpleType
import co.anbora.labs.fantom.lang.core.psi.FantomSlotExpr
import co.anbora.labs.fantom.lang.core.psi.FantomStaticCall
import co.anbora.labs.fantom.lang.core.psi.FantomType
import co.anbora.labs.fantom.lang.core.psi.FantomTypes
import co.anbora.labs.fantom.lang.resolve.FantomReference
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement

/**
 * Colours that the lexer cannot give.
 *
 * Identifier uses are coloured by what they resolve to (local, parameter, field,
 * method, type). An identifier that does not resolve gets no colour and no error:
 * the library model (`sys`) is not there yet, so `Str`, `echo` and `toStr` must
 * stay neutral. Each [FantomId] resolves once per pass.
 */
class HighlightingAnnotator: Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val color = when {
            // `enum` and `facet` are lexed as IDENTIFIER and remapped by the parser,
            // so the lexer based highlighter does not see them as keywords.
            element is LeafPsiElement && (element.elementType == FantomTypes.ENUM || element.elementType == FantomTypes.FACET) -> FantomColors.KEY_WORD
            element is FantomId -> highlightId(element)
            element.parent is FantomFacetItems && element is FantomSimpleType -> FantomColors.ANNOTATIONS
            element is FantomType -> highlightTypes(element)
            else -> null
        } ?: return
        val severity = color.testSeverity
        holder.newSilentAnnotation(severity).textAttributes(color.textAttributesKey).create()
    }

    private fun highlightId(id: FantomId): FantomColors? {
        val ref = id.reference as? FantomReference ?: return null
        // `@Foo` facets keep the annotation colour set on the simpleType.
        val parent = id.parent
        if (parent is FantomSimpleType && parent.parent is FantomFacetItems) return null
        val targets = ref.resolveAll()
        if (targets.isEmpty()) return unresolvedSlotColor(id)
        // One colour only when every target agrees; else leave the id alone.
        return targets.mapTo(HashSet()) { colorOf(it) }.singleOrNull()
    }

    private fun colorOf(target: FantomNamedElement): FantomColors? = when (target) {
        is FantomLocalDef, is FantomCatchDef -> FantomColors.LOCAL_VARIABLE
        is FantomParam, is FantomClosureParam -> FantomColors.PARAMETER
        is FantomFieldDef, is FantomEnumValDef -> FantomColors.INSTANCE_FIELD
        is FantomMethodDef, is FantomCtorDef -> FantomColors.FUNCTION_CALL
        is FantomClassDef, is FantomMixinDef, is FantomEnumDef, is FantomFacetDef -> FantomColors.TYPES
        else -> null
    }

    // Old look for `recv.name` and `Type.name` when the slot is not in the project.
    private fun unresolvedSlotColor(id: FantomId): FantomColors? {
        val slotExpr = id.parent?.parent as? FantomSlotExpr ?: return null
        val chain = slotExpr.parent
        return if (chain is FantomStaticCall || chain is FantomDotCall) FantomColors.METHOD_CALL else null
    }

    // Types in `Type(args)`, `Type[...]` and `Type.slot` are left to the id branch:
    // they get a colour only when the type resolves.
    private fun highlightTypes(element: PsiElement): FantomColors? {
        val parent = element.parent
        return if (parent is FantomSimple || parent is FantomList || parent is FantomStaticCall) {
            null
        } else FantomColors.TYPES
    }
}
