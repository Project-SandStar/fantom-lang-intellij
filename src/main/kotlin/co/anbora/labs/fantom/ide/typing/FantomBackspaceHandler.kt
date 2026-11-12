package co.anbora.labs.fantom.ide.typing

import co.anbora.labs.fantom.ide.typing.FantomTypingUtil.isFantom
import com.intellij.codeInsight.editorActions.BackspaceHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile

/** Backspace inside an empty `||`, `""`, `''` or `` `` `` pair removes both characters. */
class FantomBackspaceHandler : BackspaceHandlerDelegate() {

    private var removePartner = false

    override fun beforeCharDeleted(c: Char, file: PsiFile, editor: Editor) {
        removePartner = false
        if (!isFantom(file)) return
        if (c != '|' && c != '"' && c != '\'' && c != '`') return
        val offset = editor.caretModel.offset
        val chars = editor.document.charsSequence
        removePartner = offset < chars.length && chars[offset] == c
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
}
