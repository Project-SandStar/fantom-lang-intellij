package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.trio.TrioFileType
import co.anbora.labs.fantom.lang.trio.psi.TrioDictItem
import co.anbora.labs.fantom.lang.trio.psi.TrioRecord
import co.anbora.labs.fantom.lang.trio.psi.TrioTag
import co.anbora.labs.fantom.lang.trio.psi.TrioTypes
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType

/** Helpers shared by the Trio parser tests. */
object TrioTestSupport {

    fun parse(project: Project, text: String): PsiFile =
        PsiFileFactory.getInstance(project).createFileFromText("a.trio", TrioFileType, text)

    /**
     * The tree in a short form, one node on a line:
     * `record`, `tag name`, and for a value its kind and text (`str "a"`).
     */
    fun tree(file: PsiFile): String {
        val out = StringBuilder()
        var child = file.firstChild
        while (child != null) {
            dump(child, 0, out)
            child = child.nextSibling
        }
        return out.toString().trimEnd()
    }

    private fun dump(element: PsiElement, depth: Int, out: StringBuilder) {
        val pad = "  ".repeat(depth)
        when {
            element is PsiWhiteSpace -> return
            element is PsiComment -> out.append(pad).append("comment\n")
            element is PsiErrorElement -> out.append(pad).append("ERROR ").append(element.errorDescription).append('\n')
            element.elementType == TrioTypes.SEPARATOR -> out.append(pad).append("separator\n")
            element is TrioRecord -> {
                out.append(pad).append("record\n")
                for (child in element.children) dump(child, depth + 1, out)
            }
            element is TrioTag -> {
                out.append(pad).append("tag ").append(element.name).append('\n')
                for (child in element.children) dump(child, depth + 1, out)
            }
            element is TrioDictItem -> {
                out.append(pad).append("item ").append(element.firstChild.text).append('\n')
                for (child in element.children) dump(child, depth + 1, out)
            }
            else -> {
                val kind = element.node.elementType.toString()
                val nested = element.children.filter { it !is PsiWhiteSpace }
                if (nested.isEmpty()) {
                    out.append(pad).append(kind).append(' ').append(element.text.replace("\n", "\\n")).append('\n')
                } else {
                    out.append(pad).append(kind).append('\n')
                    for (child in nested) dump(child, depth + 1, out)
                }
            }
        }
    }

    /** Syntax errors and bad characters, as `line: message`. */
    fun problems(file: PsiFile): List<String> {
        val text = file.text
        fun line(offset: Int): Int = text.substring(0, offset).count { it == '\n' } + 1
        val out = ArrayList<String>()
        for (error in PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)) {
            out += "${line(error.textRange.startOffset)}: ${error.errorDescription}"
        }
        var leaf = PsiTreeUtil.firstChild(file)
        while (true) {
            if (leaf.elementType == TokenType.BAD_CHARACTER) out += "${line(leaf.textRange.startOffset)}: bad character"
            leaf = PsiTreeUtil.nextLeaf(leaf) ?: break
        }
        return out.distinct()
    }
}
