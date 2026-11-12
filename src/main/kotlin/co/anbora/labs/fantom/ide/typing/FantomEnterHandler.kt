package co.anbora.labs.fantom.ide.typing

import co.anbora.labs.fantom.ide.typing.FantomTypingUtil.firstNonBlankAfter
import co.anbora.labs.fantom.ide.typing.FantomTypingUtil.isFantom
import co.anbora.labs.fantom.ide.typing.FantomTypingUtil.lastNonBlankBefore
import co.anbora.labs.fantom.ide.typing.FantomTypingUtil.lineIndent
import co.anbora.labs.fantom.ide.typing.FantomTypingUtil.tokenAt
import co.anbora.labs.fantom.lang.core.STRING_LITERALS
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.FANDOC_LINE_COMMENT
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate.Result
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiFile

private const val STEP = "  "

/**
 * Enter in Fantom code. There is no formatter yet, so the new line copies the indent of the
 * current line and adds two spaces after `{`. Enter inside a `**` fandoc comment continues it.
 */
class FantomEnterHandler : EnterHandlerDelegateAdapter() {

    override fun preprocessEnter(
        file: PsiFile,
        editor: Editor,
        caretOffset: Ref<Int>,
        caretAdvance: Ref<Int>,
        dataContext: DataContext,
        originalHandler: EditorActionHandler?
    ): Result {
        if (!isFantom(file)) return Result.Continue
        if (editor.selectionModel.hasSelection()) return Result.Continue
        val document = editor.document
        val offset = caretOffset.get()
        val chars = document.charsSequence
        val indent = lineIndent(document, offset)

        val before = tokenAt(editor, offset - 1)
        if (before == FANDOC_LINE_COMMENT) {
            return insert(editor, caretOffset, offset, "\n$indent** ")
        }
        if (before != null && STRING_LITERALS.contains(before)) return Result.Continue

        val last = lastNonBlankBefore(document, offset)
        if (last >= 0 && chars[last] == '{') {
            val next = firstNonBlankAfter(document, offset)
            if (next >= 0 && chars[next] == '}') {
                document.deleteString(offset, next)
                document.insertString(offset, "\n$indent$STEP\n$indent")
                return moveTo(editor, caretOffset, offset + 1 + indent.length + STEP.length)
            }
            return insert(editor, caretOffset, offset, "\n$indent$STEP")
        }
        return insert(editor, caretOffset, offset, "\n$indent")
    }

    private fun insert(editor: Editor, caretOffset: Ref<Int>, offset: Int, text: String): Result {
        editor.document.insertString(offset, text)
        return moveTo(editor, caretOffset, offset + text.length)
    }

    private fun moveTo(editor: Editor, caretOffset: Ref<Int>, offset: Int): Result {
        editor.caretModel.moveToOffset(offset)
        caretOffset.set(offset)
        return Result.Stop
    }
}
