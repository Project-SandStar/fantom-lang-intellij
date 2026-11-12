package co.anbora.labs.fantom.ide.typing

import co.anbora.labs.fantom.ide.typing.FantomTypingUtil.isFantom
import co.anbora.labs.fantom.ide.typing.FantomTypingUtil.isInStringOrComment
import co.anbora.labs.fantom.ide.typing.FantomTypingUtil.lineIndent
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.LBRACE
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.RBRACE
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Typing `|` where a closure can start inserts `||` with the caret in between, typing `|`
 * before a `|` skips it, and typing `}` on a line of its own moves it to the indent of the
 * matching `{`.
 */
class FantomTypedHandler : TypedHandlerDelegate() {

    override fun beforeCharTyped(c: Char, project: Project, editor: Editor, file: PsiFile, fileType: FileType): Result {
        if (c != '|' || !isFantom(file)) return Result.CONTINUE
        val document = editor.document
        val offset = editor.caretModel.offset
        val chars = document.charsSequence
        if (isInStringOrComment(editor, offset)) return Result.CONTINUE

        if (offset < chars.length && chars[offset] == '|') {
            editor.caretModel.moveToOffset(offset + 1)
            return Result.STOP
        }
        if (offset > 0 && chars[offset - 1] == '|') return Result.CONTINUE
        if (offset < chars.length && Character.isUnicodeIdentifierPart(chars[offset])) return Result.CONTINUE
        if (!closureCanStart(document, offset)) return Result.CONTINUE

        document.insertString(offset, "||")
        editor.caretModel.moveToOffset(offset + 1)
        return Result.STOP
    }

    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (c != '}' || !isFantom(file)) return Result.CONTINUE
        val document = editor.document
        val offset = editor.caretModel.offset - 1
        if (offset < 0 || document.charsSequence[offset] != '}') return Result.CONTINUE
        if (isInStringOrComment(editor, offset)) return Result.CONTINUE
        val line = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(line)
        if (document.charsSequence.subSequence(lineStart, offset).isNotBlank()) return Result.CONTINUE

        val open = matchingOpenBrace(editor, offset) ?: return Result.CONTINUE
        val indent = lineIndent(document, open)
        document.replaceString(lineStart, offset, indent)
        return Result.STOP
    }

    private fun closureCanStart(document: Document, offset: Int): Boolean {
        val chars = document.charsSequence
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        var i = offset - 1
        while (i >= lineStart && (chars[i] == ' ' || chars[i] == '\t')) i--
        if (i < lineStart) return true // statement start
        when (chars[i]) {
            '(', ',', '=', '{', ';', '}' -> return true
        }
        var start = i
        while (start >= lineStart && Character.isJavaIdentifierPart(chars[start])) start--
        return chars.subSequence(start + 1, i + 1).toString() == "return"
    }

    private fun matchingOpenBrace(editor: Editor, closeOffset: Int): Int? {
        val iterator = editor.highlighter.createIterator(closeOffset)
        if (iterator.atEnd() || iterator.tokenType != RBRACE) return null
        var depth = 0
        while (true) {
            when (iterator.tokenType) {
                RBRACE -> depth++
                LBRACE -> if (--depth == 0) return iterator.start
            }
            iterator.retreat()
            if (iterator.atEnd()) return null
        }
    }
}
