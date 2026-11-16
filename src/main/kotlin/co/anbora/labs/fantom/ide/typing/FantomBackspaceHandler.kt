package co.anbora.labs.fantom.ide.typing

import co.anbora.labs.fantom.ide.typing.FantomTypingUtil.closureCanStart
import co.anbora.labs.fantom.ide.typing.FantomTypingUtil.isFantom
import co.anbora.labs.fantom.ide.typing.FantomTypingUtil.isStringOrComment
import co.anbora.labs.fantom.ide.typing.FantomTypingUtil.tokenAt
import com.intellij.codeInsight.editorActions.BackspaceHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile

/**
 * Backspace inside an empty `||`, `""`, `''` or `` `` `` pair removes both characters.
 *
 * Only the opening char of an empty pair counts: a quote that starts its highlighter token
 * (not the closing quote of an earlier literal, as in `"a"<caret>"b"`), or a `|` where a
 * closure can start (not the first half of the `||` operator, as in `a |<caret>| b`).
 */
class FantomBackspaceHandler : BackspaceHandlerDelegate() {

    private var removePartner = false

    override fun beforeCharDeleted(c: Char, file: PsiFile, editor: Editor) {
        removePartner = false
        if (!isFantom(file)) return
        if (c != '|' && c != '"' && c != '\'' && c != '`') return
        val offset = editor.caretModel.offset
        val chars = editor.document.charsSequence
        if (offset <= 0 || offset >= chars.length || chars[offset] != c || chars[offset - 1] != c) return
        removePartner = if (c == '|') opensClosureParams(editor, offset - 1) else opensLiteral(editor, offset - 1)
    }

    override fun charDeleted(c: Char, file: PsiFile, editor: Editor): Boolean {
        if (!removePartner) return false
        removePartner = false
        val offset = editor.caretModel.offset
        val chars = editor.document.charsSequence
        if (offset < chars.length && chars[offset] == c) {
            editor.document.deleteString(offset, offset + 1)
            return true
        }
        return false
    }

    /** The quote at [quote] starts its token, so it opens a literal rather than closing one. */
    private fun opensLiteral(editor: Editor, quote: Int): Boolean {
        val iterator = editor.highlighter.createIterator(quote)
        return !iterator.atEnd() && iterator.start == quote
    }

    /** The `|` at [pipe] is outside strings and comments and sits where a closure can start. */
    private fun opensClosureParams(editor: Editor, pipe: Int): Boolean {
        if (isStringOrComment(tokenAt(editor, pipe))) return false
        return closureCanStart(editor.document, pipe)
    }
}
