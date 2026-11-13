package co.anbora.labs.fantom.ide.todo

import co.anbora.labs.fantom.lang.FantomParserDefinition.Companion.C_STYLE_COMMENT
import co.anbora.labs.fantom.lang.FantomParserDefinition.Companion.END_OF_LINE_COMMENT
import co.anbora.labs.fantom.lang.core.psi.FantomFile
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.FANDOC_LINE_COMMENT
import co.anbora.labs.fantom.lang.core.tokenSetOf
import co.anbora.labs.fantom.lang.lexer.FantomLexer
import com.intellij.lexer.Lexer
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.search.IndexPatternBuilder
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

/** TODO and FIXME in `//`, `/* */` and `**` comments: the TODO tool window and editor highlighting. */
class FantomIndexPatternBuilder : IndexPatternBuilder {

    private val comments = tokenSetOf(END_OF_LINE_COMMENT, C_STYLE_COMMENT, FANDOC_LINE_COMMENT)

    override fun getIndexingLexer(file: PsiFile): Lexer? = if (file is FantomFile) FantomLexer() else null

    override fun getCommentTokenSet(file: PsiFile): TokenSet? = if (file is FantomFile) comments else null

    override fun getCommentStartDelta(tokenType: IElementType?): Int = if (tokenType in comments) 2 else 0

    override fun getCommentEndDelta(tokenType: IElementType?): Int = if (tokenType == C_STYLE_COMMENT) 2 else 0
}
