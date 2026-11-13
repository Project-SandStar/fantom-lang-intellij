package co.anbora.labs.fantom.lang.fandoc

import co.anbora.labs.fantom.ide.color.FantomColors
import co.anbora.labs.fantom.ide.icons.FantomIcons
import co.anbora.labs.fantom.lang.axon.FlatPsiParser
import co.anbora.labs.fantom.lang.text.LineLexerBase
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon

/** Fandoc documents (`.fandoc`): colours only, no PSI tree. */
object FandocLanguage : Language("fandoc_lang") {
    private fun readResolve(): Any = FandocLanguage
    override fun getDisplayName(): String = "Fandoc"
}

class FandocElementType(debugName: String) : IElementType(debugName, FandocLanguage) {
    override fun toString(): String = "Fandoc:" + super.toString()
}

object FandocTypes {
    /** `** title: ...` header lines and `****` rules. */
    @JvmField val META = FandocElementType("META")
    @JvmField val HEADING = FandocElementType("HEADING")
    /** A line of `#`, `*`, `=` or `-` under a heading. */
    @JvmField val HEADING_MARK = FandocElementType("HEADING_MARK")
    @JvmField val LIST_MARKER = FandocElementType("LIST_MARKER")
    /** A `pre>` ... `<pre` block, markers included. */
    @JvmField val PRE = FandocElementType("PRE")
    @JvmField val CODE = FandocElementType("CODE")
    @JvmField val LINK = FandocElementType("LINK")
    @JvmField val TEXT = FandocElementType("TEXT")
}

/**
 * Line lexer for fandoc. State 0 is a line start, 1 the middle of a line.
 * A heading is a line whose next line is a run of 3+ `#`, `*`, `=` or `-`.
 */
class FandocLexer : LineLexerBase() {

    override fun lex(state: Int): Int {
        val c = buf[pos]
        if (c == '\n' || c == '\r') return token(skipNewline(pos), TokenType.WHITE_SPACE, 0)
        if (state == 0) {
            if (isMarkLine(pos)) return token(lineEnd(pos), FandocTypes.HEADING_MARK, 1)
            if (startsWith(pos, "**")) return token(lineEnd(pos), FandocTypes.META, 1)
            if (startsWith(pos, "pre>")) return token(preEnd(pos), FandocTypes.PRE, 1)
            val text = skipSpaces(pos)
            if (text > pos) return token(text, TokenType.WHITE_SPACE, if (text < limit) 2 else 0)
            val end = lineEnd(pos)
            if (end > pos && end < limit && isMarkLine(skipNewline(end))) return token(end, FandocTypes.HEADING, 1)
            val marker = listMarkerEnd(pos)
            if (marker > 0) return token(marker, FandocTypes.LIST_MARKER, 1)
            return inline()
        }
        if (state == 2) {
            // After the indent of a line: a list marker may follow.
            val marker = listMarkerEnd(pos)
            if (marker > 0) return token(marker, FandocTypes.LIST_MARKER, 1)
            return inline()
        }
        return inline()
    }

    private fun inline(): Int {
        val c = buf[pos]
        if (c == '`') return token(closing(pos, '`'), FandocTypes.LINK, 1)
        if (c == '\'' && (pos == 0 || !buf[pos - 1].isLetterOrDigit())) {
            val end = closing(pos, '\'')
            if (end > pos + 2 && buf[end - 1] == '\'') return token(end, FandocTypes.CODE, 1)
        }
        var end = pos + 1
        while (end < limit) {
            val ch = buf[end]
            if (ch == '\n' || ch == '\r' || ch == '`') break
            if (ch == '\'' && !buf[end - 1].isLetterOrDigit()) break
            end++
        }
        return token(end, FandocTypes.TEXT, 1)
    }

    /** End of a span that starts with [q] at [from] and ends with [q] on the same line (or at the line end). */
    private fun closing(from: Int, q: Char): Int {
        var p = from + 1
        while (p < limit && buf[p] != '\n' && buf[p] != '\r') {
            if (buf[p] == q) return p + 1
            p++
        }
        return p
    }

    /** `- `, `* `, `1. `, `a. `, `I. `: the marker end, or 0. */
    private fun listMarkerEnd(at: Int): Int {
        if (at >= limit) return 0
        val c = buf[at]
        if ((c == '-' || c == '*') && at + 1 < limit && buf[at + 1] == ' ') return at + 1
        var p = at
        while (p < limit && buf[p].isLetterOrDigit() && p - at < 4) p++
        if (p > at && p < limit && buf[p] == '.' && p + 1 < limit && buf[p + 1] == ' ' &&
            (buf[at].isDigit() || p - at == 1 || buf.subSequence(at, p).all { it in "IVXivx" })) return p + 1
        return 0
    }

    private fun isMarkLine(at: Int): Boolean {
        if (at >= limit) return false
        val c = buf[at]
        if (c != '#' && c != '*' && c != '=' && c != '-') return false
        val end = lineEnd(at)
        if (end - at < 3) return false
        for (i in at until end) if (buf[i] != c) return buf.subSequence(i, end).isBlank()
        return true
    }

    private fun preEnd(from: Int): Int {
        var p = lineEnd(from)
        while (p < limit) {
            p = skipNewline(p)
            if (startsWith(p, "<pre")) return lineEnd(p)
            p = lineEnd(p)
        }
        return p
    }
}

class FandocDocFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, FandocLanguage) {
    override fun getFileType(): FileType = FandocFileType
    override fun toString(): String = "Fandoc File"
}

object FandocFileType : LanguageFileType(FandocLanguage) {
    override fun getName(): String = "Fandoc"
    override fun getDescription(): String = "Fantom fandoc document"
    override fun getDefaultExtension(): String = "fandoc"
    override fun getIcon(): Icon = FantomIcons.FILE
}

class FandocParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = FandocLexer()
    override fun createParser(project: Project?): PsiParser = FlatPsiParser
    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = TokenSet.EMPTY
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = FandocDocFile(viewProvider)

    companion object {
        @JvmField val FILE = IFileElementType(FandocLanguage)
    }
}

class FandocSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = FandocLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(
        when (tokenType) {
            FandocTypes.META -> META
            FandocTypes.HEADING -> HEADING
            FandocTypes.HEADING_MARK -> HEADING_MARK
            FandocTypes.LIST_MARKER -> LIST_MARKER
            FandocTypes.PRE -> PRE
            FandocTypes.CODE -> CODE
            FandocTypes.LINK -> LINK
            else -> null
        }
    )

    companion object {
        val META = TextAttributesKey.createTextAttributesKey("co.anbora.labs.fandoc.META", FantomColors.DOC_COMMENTS.textAttributesKey)
        val HEADING = TextAttributesKey.createTextAttributesKey("co.anbora.labs.fandoc.HEADING", FantomColors.KEY_WORD.textAttributesKey)
        val HEADING_MARK = TextAttributesKey.createTextAttributesKey("co.anbora.labs.fandoc.HEADING_MARK", FantomColors.COMMENTS.textAttributesKey)
        val LIST_MARKER = TextAttributesKey.createTextAttributesKey("co.anbora.labs.fandoc.LIST_MARKER", FantomColors.OPERATOR.textAttributesKey)
        val PRE = TextAttributesKey.createTextAttributesKey("co.anbora.labs.fandoc.PRE", FantomColors.FANDOC_TAG.textAttributesKey)
        val CODE = TextAttributesKey.createTextAttributesKey("co.anbora.labs.fandoc.CODE", FantomColors.FANDOC_TAG.textAttributesKey)
        val LINK = TextAttributesKey.createTextAttributesKey("co.anbora.labs.fandoc.LINK", DefaultLanguageHighlighterColors.HIGHLIGHTED_REFERENCE)
    }
}

class FandocSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = FandocSyntaxHighlighter()
}
