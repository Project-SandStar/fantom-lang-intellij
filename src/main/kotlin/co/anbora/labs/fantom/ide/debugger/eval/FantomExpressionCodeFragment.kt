package co.anbora.labs.fantom.ide.debugger.eval

import co.anbora.labs.fantom.lang.FantomLanguage
import co.anbora.labs.fantom.lang.core.parser.FantomParser
import co.anbora.labs.fantom.lang.lexer.FantomLexer
import com.intellij.lang.ASTNode
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiBuilderFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import co.anbora.labs.fantom.lang.FantomFileType
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiCodeFragment
import com.intellij.psi.PsiManager
import com.intellij.psi.SingleRootFileViewProvider
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType

/**
 * The text of an Evaluate Expression box, a watch or a breakpoint condition in a `.fan` frame.
 * It is parsed with the plugin's own parser, starting at the `expr` rule instead of a
 * compilation unit; the [context] is the element at the debugger position.
 *
 * A Fantom file on a [LightVirtualFile] with its own root element type. The platform's
 * `PsiCodeFragmentImpl` would do the same, but it is internal API.
 */
class FantomExpressionCodeFragment private constructor(viewProvider: FileViewProvider, private val contextElement: PsiElement?) :
    PsiFileBase(viewProvider, FantomLanguage), PsiCodeFragment {

    init {
        init(FantomExpressionFragmentElementType, FantomExpressionFragmentElementType)
    }

    private var resolveScope: GlobalSearchScope? = null

    override fun getFileType(): FileType = FantomFileType
    override fun getContext(): PsiElement? = contextElement
    override fun forceResolveScope(scope: GlobalSearchScope?) {
        resolveScope = scope
    }
    override fun getForcedResolveScope(): GlobalSearchScope? = resolveScope
    /**
     * Completion and other features work on a copy. Like the platform's code fragments, the copy
     * is non-physical (no PSI events) and its original file is this fragment.
     */
    override fun clone(): PsiFileImpl =
        create(project, name, text, contextElement, physical = false).also {
            it.resolveScope = resolveScope
            it.originalFile = this
        }
    override fun toString(): String = "Fantom expression fragment"

    companion object {
        fun create(
            project: Project, name: String, text: CharSequence, context: PsiElement?, physical: Boolean = true,
        ): FantomExpressionCodeFragment {
            val file = LightVirtualFile(name, FantomLanguage, text)
            val viewProvider = SingleRootFileViewProvider(PsiManager.getInstance(project), file, physical)
            val fragment = FantomExpressionCodeFragment(viewProvider, context)
            viewProvider.forceCachedPsi(fragment)
            return fragment
        }
    }
}

/** Root element type of [FantomExpressionCodeFragment]: one Fantom expression. */
object FantomExpressionFragmentElementType : IFileElementType("FANTOM_EXPRESSION_FRAGMENT", FantomLanguage) {
    /**
     * Parses without asking [chameleon] for its PSI. After Undo or a paste replaces all of the
     * text, the platform reparses into a fresh root that has no PSI; the default
     * `parseContents` would then ask the parser definition to create one for this element
     * type, which fails ("Unknown element type: FANTOM_EXPRESSION_FRAGMENT").
     */
    override fun parseContents(chameleon: ASTNode): ASTNode? {
        val builder = PsiBuilderFactory.getInstance().createBuilder(
            LanguageParserDefinitions.INSTANCE.forLanguage(FantomLanguage), FantomLexer(), chameleon.chars,
        )
        return FantomExpressionParser().parse(this, builder).firstChildNode
    }

    override fun doParseContents(chameleon: ASTNode, psi: PsiElement): ASTNode? = parseContents(chameleon)
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
