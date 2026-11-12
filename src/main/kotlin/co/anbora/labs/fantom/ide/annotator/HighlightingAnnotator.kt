package co.anbora.labs.fantom.ide.annotator

import co.anbora.labs.fantom.ide.color.FantomColors
import co.anbora.labs.fantom.ide.presentation.FantomPresentation
import co.anbora.labs.fantom.lang.core.psi.FantomCatchDef
import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomClosureParam
import co.anbora.labs.fantom.lang.core.psi.FantomClosureSig
import co.anbora.labs.fantom.lang.core.psi.FantomCtorDef
import co.anbora.labs.fantom.lang.core.psi.FantomDotCall
import co.anbora.labs.fantom.lang.core.psi.FantomEnumDef
import co.anbora.labs.fantom.lang.core.psi.FantomEnumValDef
import co.anbora.labs.fantom.lang.core.psi.FantomFacetDef
import co.anbora.labs.fantom.lang.core.psi.FantomFacetItems
import co.anbora.labs.fantom.lang.core.psi.FantomFacetVal
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomFuncType
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
import co.anbora.labs.fantom.lang.core.tokenSetOf
import co.anbora.labs.fantom.lang.resolve.FantomReference
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Colours that the lexer cannot give. Every annotation is silent, with
 * [FantomColors.testSeverity] (an INFORMATION level) and a text attributes key.
 *
 * - Declaration names: class, method, field, const, static, enum value.
 * - Identifier uses: coloured by what they resolve to (local, parameter, field,
 *   method, type). An identifier that does not resolve gets no colour and no error:
 *   `Str`, `echo` and `toStr` must stay neutral without a library model.
 *   Each [FantomId] resolves once per pass.
 * - Ranges inside one token: `$x` and `${...}` and `\n` in strings, `` `code` ``
 *   in fandoc comments.
 * - `@Facet`, and the `|` pair of a closure signature or function type.
 */
class HighlightingAnnotator: Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when {
            element is LeafPsiElement -> annotateLeaf(element, holder)
            element is FantomId -> paint(holder, highlightId(element), element.textRange)
            element is FantomFacetItems -> annotateFacet(element, holder)
            element is FantomClosureSig || element is FantomFuncType -> annotatePipes(element, holder)
        }
    }

    private fun paint(holder: AnnotationHolder, color: FantomColors?, range: TextRange) {
        if (color == null || range.isEmpty) return
        holder.newSilentAnnotation(color.testSeverity).range(range).textAttributes(color.textAttributesKey).create()
    }

    // ---- leaves: remapped keywords, strings, fandoc

    private fun annotateLeaf(leaf: LeafPsiElement, holder: AnnotationHolder) {
        when (leaf.elementType) {
            // `enum` and `facet` are lexed as IDENTIFIER and remapped by the parser,
            // so the lexer based highlighter does not see them as keywords.
            FantomTypes.ENUM, FantomTypes.FACET -> paint(holder, FantomColors.KEY_WORD, leaf.textRange)
            FantomTypes.STRING_LITERAL, FantomTypes.URI_LITERAL -> annotateString(leaf, holder)
            FantomTypes.FANDOC_LINE_COMMENT -> annotateFandoc(leaf, holder)
        }
    }

    /** `$name`, `$a.b`, `${expr}`, `$<pod.key>` and `\x` escapes inside a string or uri token. */
    private fun annotateString(leaf: LeafPsiElement, holder: AnnotationHolder) {
        val text = leaf.text
        val base = leaf.textRange.startOffset
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '\\' && i + 1 < n) {
                val end = if (text[i + 1] == 'u') minOf(n, i + 6) else i + 2
                paint(holder, FantomColors.STRING_ESCAPE, TextRange(base + i, base + end))
                i = end
                continue
            }
            if (c == '$' && i + 1 < n) {
                val end = interpolationEnd(text, i)
                if (end > i + 1) {
                    paint(holder, FantomColors.STRING_INTERPOLATION, TextRange(base + i, base + end))
                    i = end
                    continue
                }
            }
            i++
        }
    }

    /** End offset (exclusive) of the interpolation that starts at [start], or `start + 1` when `$` is bare. */
    private fun interpolationEnd(text: String, start: Int): Int {
        val n = text.length
        var i = start + 1
        when (text[i]) {
            '{' -> {
                var depth = 0
                while (i < n) {
                    when (text[i]) {
                        '{' -> depth++
                        '}' -> if (--depth == 0) return i + 1
                    }
                    i++
                }
                return n
            }
            '<' -> {
                while (i < n && text[i] != '>' && text[i] != '"' && text[i] != '`') i++
                return if (i < n && text[i] == '>') i + 1 else start + 1
            }
            else -> {
                if (!isIdStart(text[i])) return start + 1
                while (i < n) {
                    if (isIdPart(text[i])) i++
                    else if (text[i] == '.' && i + 1 < n && isIdStart(text[i + 1])) i++
                    else break
                }
                return i
            }
        }
    }

    private fun isIdStart(c: Char) = c == '_' || c.isLetter()
    private fun isIdPart(c: Char) = c == '_' || c.isLetterOrDigit()

    /** `` `code` `` spans inside a `**` comment block; a span never crosses a line. */
    private fun annotateFandoc(leaf: LeafPsiElement, holder: AnnotationHolder) {
        val text = leaf.text
        val base = leaf.textRange.startOffset
        var open = -1
        for (i in text.indices) {
            when (text[i]) {
                '\n', '\r' -> open = -1
                '`' -> if (open < 0) open = i else {
                    paint(holder, FantomColors.FANDOC_TAG, TextRange(base + open, base + i + 1))
                    open = -1
                }
            }
        }
    }

    // ---- `@Facet`, closure pipes

    private fun annotateFacet(facet: FantomFacetItems, holder: AnnotationHolder) {
        val name = facet.simpleType ?: return
        paint(holder, FantomColors.FACET, TextRange(facet.textRange.startOffset, name.textRange.endOffset))
    }

    private fun annotatePipes(sig: PsiElement, holder: AnnotationHolder) {
        for (pipe in sig.node.getChildren(PIPES)) {
            paint(holder, FantomColors.CLOSURE_PIPE, pipe.textRange)
        }
    }

    // ---- identifiers

    private fun highlightId(id: FantomId): FantomColors? {
        val parent = id.parent
        // Declaration names and facet value names have no reference.
        if (parent is FantomNamedElement) return declarationColor(parent)
        if (parent is FantomFacetVal) return FantomColors.ANNOTATIONS
        // `@Foo` facets keep the facet colour set on the whole `@Foo` range.
        if (parent is FantomSimpleType && parent.parent is FantomFacetItems) return null
        val ref = id.reference as? FantomReference ?: return null
        val targets = ref.resolveAll()
        if (targets.isEmpty()) return unresolvedColor(id, parent)
        // One colour only when every target agrees; else leave the id alone.
        return targets.mapTo(HashSet()) { colorOf(it) }.singleOrNull()
    }

    private fun declarationColor(decl: FantomNamedElement): FantomColors? = when (decl) {
        is FantomClassDef, is FantomMixinDef, is FantomEnumDef, is FantomFacetDef -> FantomColors.CLASS_DECLARATION
        is FantomMethodDef -> if (isStatic(decl)) FantomColors.STATIC_METHOD else FantomColors.METHOD_DECLARATION
        is FantomCtorDef -> FantomColors.METHOD_DECLARATION
        is FantomFieldDef -> fieldColor(decl, FantomColors.FIELD_DECLARATION)
        is FantomEnumValDef -> FantomColors.CONSTANT
        // Locals, parameters and catch params: the declaration is plain, like in Java.
        else -> null
    }

    private fun colorOf(target: FantomNamedElement): FantomColors? = when (target) {
        is FantomLocalDef, is FantomCatchDef -> FantomColors.LOCAL_VARIABLE
        is FantomParam, is FantomClosureParam -> FantomColors.PARAMETER
        is FantomFieldDef -> fieldColor(target, FantomColors.INSTANCE_FIELD)
        is FantomEnumValDef -> FantomColors.CONSTANT
        is FantomMethodDef -> if (isStatic(target)) FantomColors.STATIC_METHOD else FantomColors.FUNCTION_CALL
        is FantomCtorDef -> FantomColors.FUNCTION_CALL
        is FantomClassDef, is FantomMixinDef, is FantomEnumDef, is FantomFacetDef -> FantomColors.TYPE_REFERENCE
        else -> null
    }

    private fun fieldColor(field: FantomFieldDef, instance: FantomColors): FantomColors {
        val mods = FantomPresentation.modifiers(field)
        return when {
            "const" in mods -> FantomColors.CONSTANT
            "static" in mods -> FantomColors.STATIC_FIELD
            else -> instance
        }
    }

    private fun isStatic(slot: FantomNamedElement) = "static" in FantomPresentation.modifiers(slot)

    /**
     * No target: the old look. `recv.name` and `Type.name` keep the unresolved slot
     * colour; a type name in a type position keeps the unresolved type colour. Types
     * in `Type(args)`, `Type[...]` and `Type.slot` stay bare, so library names never
     * look wrong without a library model.
     */
    private fun unresolvedColor(id: FantomId, parent: PsiElement): FantomColors? {
        if (parent is FantomSimpleType) {
            val type = PsiTreeUtil.getParentOfType(parent, FantomType::class.java) ?: return null
            val owner = type.parent
            return if (owner is FantomSimple || owner is FantomList || owner is FantomStaticCall) null else FantomColors.TYPES
        }
        val slotExpr = parent.parent as? FantomSlotExpr ?: return null
        val chain = slotExpr.parent
        return if (chain is FantomStaticCall || chain is FantomDotCall) FantomColors.METHOD_CALL else null
    }

    private companion object {
        val PIPES = tokenSetOf(FantomTypes.OR)
    }
}
