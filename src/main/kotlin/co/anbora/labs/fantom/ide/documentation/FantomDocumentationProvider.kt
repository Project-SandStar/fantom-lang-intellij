package co.anbora.labs.fantom.ide.documentation

import co.anbora.labs.fantom.ide.presentation.FantomPresentation
import co.anbora.labs.fantom.lang.core.psi.FantomCatchDef
import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomClosureParam
import co.anbora.labs.fantom.lang.core.psi.FantomCtorDef
import co.anbora.labs.fantom.lang.core.psi.FantomEnumDef
import co.anbora.labs.fantom.lang.core.psi.FantomEnumValDef
import co.anbora.labs.fantom.lang.core.psi.FantomFacetDef
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomId
import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomMixinDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomParam
import co.anbora.labs.fantom.lang.core.psi.FantomTypeHeader
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.FANDOC_LINE_COMMENT
import co.anbora.labs.fantom.lang.resolve.FantomScope
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace

/**
 * Quick Documentation (Ctrl+Q) for Fantom declarations: a definition block with
 * the flags and signature, the owning type for slots, then the fandoc (`**` lines
 * above the declaration) as minimal HTML.
 */
class FantomDocumentationProvider : AbstractDocumentationProvider() {

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        val decl = declarationOf(element) ?: return null
        return escape(signature(decl))
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val decl = declarationOf(element) ?: return null
        val sb = StringBuilder()
        sb.append(DocumentationMarkup.DEFINITION_START)
        val owner = ownerName(decl)
        if (owner != null) {
            sb.append(DocumentationMarkup.GRAYED_START).append(escape(owner)).append(DocumentationMarkup.GRAYED_END).append("<br>")
        }
        sb.append(escape(signature(decl)))
        sb.append(DocumentationMarkup.DEFINITION_END)
        val html = fandocHtml(fandocLines(decl))
        if (html.isNotEmpty()) {
            sb.append(DocumentationMarkup.CONTENT_START).append(html).append(DocumentationMarkup.CONTENT_END)
        }
        return sb.toString()
    }

    companion object {

        /** The declaration for [element]: itself, or the declaration whose name identifier it is. */
        fun declarationOf(element: PsiElement?): FantomNamedElement? = when (element) {
            null -> null
            is FantomNamedElement -> element
            is FantomId -> element.parent as? FantomNamedElement
            else -> if (element.parent is FantomId) element.parent.parent as? FantomNamedElement else null
        }

        /** One-line signature: flags, keyword or name, parameters, type. */
        fun signature(decl: FantomNamedElement): String {
            val name = decl.name ?: "?"
            return when (decl) {
                is FantomClassDef -> typeSignature(decl.typeHeader, "class", name, decl.inheritance?.text)
                is FantomMixinDef -> typeSignature(decl.typeHeader, "mixin", name, decl.inheritance?.text)
                is FantomEnumDef -> typeSignature(decl.typeHeader, "enum class", name, decl.inheritance?.text)
                is FantomFacetDef -> typeSignature(decl.typeHeader, "facet class", name, decl.inheritance?.text)
                is FantomMethodDef -> withFlags(decl.methodFlags.text, FantomPresentation.text(decl))
                is FantomCtorDef -> withFlags(decl.ctorFlags.text, FantomPresentation.text(decl))
                is FantomFieldDef -> withFlags(decl.fieldFlags.text, FantomPresentation.text(decl))
                is FantomParam -> "${decl.type.text.trim()} $name" + (decl.expr?.let { " := ${it.text.trim()}" } ?: "")
                is FantomLocalDef -> (decl.type?.text?.trim()?.let { "$it " } ?: "") + name
                is FantomClosureParam -> (decl.type?.text?.trim()?.let { "$it " } ?: "") + name
                is FantomCatchDef -> (decl.type?.text?.trim()?.let { "$it " } ?: "") + name
                is FantomEnumValDef -> name
                else -> FantomPresentation.text(decl)
            }
        }

        /** The enclosing type name for slots and enum values, else null. */
        fun ownerName(decl: FantomNamedElement): String? =
            if (FantomScope.isSlot(decl)) FantomScope.enclosingType(decl)?.name else null

        /**
         * The fandoc lines above [decl], in source order, with the `**` prefix removed.
         *
         * Fandoc tokens are comments, so the parser leaves them between the nodes.
         * Walk `prevSibling` from the declaration (or from its `slotDef` / `typeDef`
         * wrapper, which spans the same text) over whitespace and FANDOC_LINE_COMMENT
         * tokens. When there is no previous sibling, continue from the parent: the
         * first slot of a type has its doc before the `slotDefs` node, as a child
         * of the type body. Any other node ends the walk.
         */
        fun fandocLines(decl: FantomNamedElement): List<String> {
            if (!(FantomScope.isTypeDef(decl) || FantomScope.isSlot(decl))) return emptyList()
            val comments = ArrayList<PsiComment>()
            var start: PsiElement = decl
            while (true) {
                val p = start.parent ?: break
                if (p is PsiFile || p.textRange != start.textRange) break
                start = p
            }
            var cur: PsiElement? = start
            outer@ while (cur != null && cur !is PsiFile) {
                var prev = cur.prevSibling
                while (prev != null) {
                    when {
                        prev is PsiWhiteSpace -> Unit
                        prev is PsiComment && prev.tokenType == FANDOC_LINE_COMMENT -> comments += prev
                        else -> break@outer
                    }
                    prev = prev.prevSibling
                }
                cur = cur.parent
            }
            comments.reverse()
            val lines = comments.flatMap { it.text.lines() }.map(::stripPrefix).toMutableList()
            if (lines.isEmpty()) {
                val header = typeHeaderOf(decl)
                header?.doc?.text?.lines()?.map(::stripPrefix)?.let { lines += it }
            }
            while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
            return lines
        }

        /** Minimal fandoc to HTML: blank line = paragraph, `` `x` `` and `'x'` = code. */
        fun fandocHtml(lines: List<String>): String {
            if (lines.isEmpty()) return ""
            val paragraphs = ArrayList<List<String>>()
            var cur = ArrayList<String>()
            for (line in lines) {
                if (line.isBlank()) {
                    if (cur.isNotEmpty()) { paragraphs += cur; cur = ArrayList() }
                } else cur += line.trim()
            }
            if (cur.isNotEmpty()) paragraphs += cur
            return paragraphs.joinToString("") { "<p>" + inline(it.joinToString(" ")) + "</p>" }
        }

        private val span = Regex("`([^`]+)`|(?<![\\w'])'([^'\\n]+?)'(?![\\w'])")

        private fun inline(text: String): String {
            val sb = StringBuilder()
            var last = 0
            for (m in span.findAll(text)) {
                sb.append(escape(text.substring(last, m.range.first)))
                val code = m.groups[1]?.value ?: m.groups[2]?.value ?: ""
                sb.append("<code>").append(escape(code)).append("</code>")
                last = m.range.last + 1
            }
            sb.append(escape(text.substring(last)))
            return sb.toString()
        }

        private fun stripPrefix(line: String): String {
            val t = line.trim()
            val body = if (t.startsWith("**")) t.substring(2) else t
            return if (body.startsWith(" ")) body.substring(1) else body
        }

        private fun typeHeaderOf(decl: FantomNamedElement): FantomTypeHeader? = when (decl) {
            is FantomClassDef -> decl.typeHeader
            is FantomMixinDef -> decl.typeHeader
            is FantomEnumDef -> decl.typeHeader
            is FantomFacetDef -> decl.typeHeader
            else -> null
        }

        private fun typeSignature(header: FantomTypeHeader, keyword: String, name: String, inheritance: String?): String {
            val flags = header.typeFlags.text.trim()
            val inh = inheritance?.trim()?.let { " $it" } ?: ""
            return withFlags(flags, "$keyword $name$inh")
        }

        private fun withFlags(flags: String?, rest: String): String {
            val f = flags?.trim().orEmpty().replace(Regex("\\s+"), " ")
            return if (f.isEmpty()) rest else "$f $rest"
        }

        fun escape(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
