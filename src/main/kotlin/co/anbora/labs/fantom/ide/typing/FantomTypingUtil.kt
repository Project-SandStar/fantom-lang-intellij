package co.anbora.labs.fantom.ide.typing

import co.anbora.labs.fantom.lang.FantomLanguage
import co.anbora.labs.fantom.lang.core.FANTOM_COMMENTS
import co.anbora.labs.fantom.lang.core.STRING_LITERALS
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

/** Small helpers shared by the typing handlers. They read the editor highlighter, not the PSI. */
internal object FantomTypingUtil {

    fun isFantom(file: PsiFile?): Boolean = file != null && file.language.isKindOf(FantomLanguage)

    /** Token type of the highlighter token that covers [offset], or null at the end of the document. */
    fun tokenAt(editor: Editor, offset: Int): IElementType? {
        if (offset < 0 || offset >= editor.document.textLength) return null
        val iterator = editor.highlighter.createIterator(offset)
        if (iterator.atEnd()) return null
        return iterator.tokenType
    }

    fun isStringOrComment(type: IElementType?): Boolean =
        type != null && (STRING_LITERALS.contains(type) || FANTOM_COMMENTS.contains(type))

    /** True when the character just before [offset] sits inside a string literal or a comment. */
    fun isInStringOrComment(editor: Editor, offset: Int): Boolean {
        if (offset <= 0) return false
        val before = tokenAt(editor, offset - 1)
        if (isStringOrComment(before)) {
            // A string that ends exactly at the caret is closed: typing after it is outside the literal.
            val iterator = editor.highlighter.createIterator(offset - 1)
            if (STRING_LITERALS.contains(before) && iterator.end == offset && closedLiteral(editor.document, iterator.start, iterator.end)) {
                return false
            }
            return true
        }
        return false
    }

    private fun closedLiteral(document: Document, start: Int, end: Int): Boolean {
        if (end - start < 2) return false
        val chars = document.charsSequence
        return chars[start] == chars[end - 1]
    }

    /** Leading whitespace of the line that holds [offset]. */
    fun lineIndent(document: Document, offset: Int): String {
        val line = document.getLineNumber(offset)
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val chars = document.charsSequence
        var i = start
        while (i < end && (chars[i] == ' ' || chars[i] == '\t')) i++
        return chars.subSequence(start, i).toString()
    }

    /** Offset of the last non-blank character before [offset] on the same line, or -1. */
    fun lastNonBlankBefore(document: Document, offset: Int): Int {
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        val chars = document.charsSequence
        var i = offset - 1
        while (i >= lineStart && (chars[i] == ' ' || chars[i] == '\t')) i--
        return if (i >= lineStart) i else -1
    }

    /** Offset of the first non-blank character at or after [offset] on the same line, or -1. */
    fun firstNonBlankAfter(document: Document, offset: Int): Int {
        val lineEnd = document.getLineEndOffset(document.getLineNumber(offset))
        val chars = document.charsSequence
        var i = offset
        while (i < lineEnd && (chars[i] == ' ' || chars[i] == '\t')) i++
        return if (i < lineEnd) i else -1
    }
}
