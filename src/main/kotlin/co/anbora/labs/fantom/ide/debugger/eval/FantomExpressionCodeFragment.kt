package co.anbora.labs.fantom.ide.debugger.eval

import co.anbora.labs.fantom.lang.FantomLanguage
import co.anbora.labs.fantom.lang.core.parser.FantomParser
import co.anbora.labs.fantom.lang.lexer.FantomLexer
import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiBuilderFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.PsiCodeFragmentImpl
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType

/**
 * The text of an Evaluate Expression box, a watch or a breakpoint condition in a `.fan` frame.
 * It is parsed with the plugin's own parser, starting at the `expr` rule instead of a
 * compilation unit; the [context] is the element at the debugger position.
 */
class FantomExpressionCodeFragment(project: Project, name: String, text: CharSequence, context: PsiElement?) :
    PsiCodeFragmentImpl(project, FantomExpressionFragmentElementType, true, name, text, context)

/** Root element type of [FantomExpressionCodeFragment]: one Fantom expression. */
object FantomExpressionFragmentElementType : IFileElementType("FANTOM_EXPRESSION_FRAGMENT", FantomLanguage) {
    override fun doParseContents(chameleon: ASTNode, psi: PsiElement): ASTNode? {
        val builder = PsiBuilderFactory.getInstance()
            .createBuilder(psi.project, chameleon, FantomLexer(), FantomLanguage, chameleon.chars)
        return FantomExpressionParser().parse(this, builder).firstChildNode
    }
}

/** [FantomParser] with `expr` as its root rule; tokens after the expression become one error. */
private class FantomExpressionParser : FantomParser() {
    override fun parse_root_(root: IElementType, builder: PsiBuilder): Boolean {
        val result = expr(builder, 1)
        if (!builder.eof()) {
            val rest = builder.mark()
            while (!builder.eof()) builder.advanceLexer()
            rest.error("End of expression expected")
        }
        return result
    }
}
