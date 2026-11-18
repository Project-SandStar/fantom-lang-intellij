package co.anbora.labs.fantom.lang.axon.psi.impl

import co.anbora.labs.fantom.lang.axon.AxonTokenTypes
import co.anbora.labs.fantom.lang.axon.psi.AxonElementImpl
import co.anbora.labs.fantom.lang.axon.psi.AxonNameUse
import co.anbora.labs.fantom.lang.axon.psi.AxonReference
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.TokenType

/** Grammar-Kit mixin for the rules that use a name: `refExpr` (`x`), `typeRefExpr` (`Str`) and `dotCallExpr` (`a.x(...)`). */
abstract class AxonNameUseImpl(node: ASTNode) : AxonElementImpl(node), AxonNameUse {

    /**
     * `refExpr` is one IDENTIFIER, `typeRefExpr` one TYPENAME. `dotCallExpr` is `target . name ...`:
     * the name is the first token after the dot, and it can be a keyword.
     */
    override val referenceNameElement: PsiElement?
        get() {
            val dot = node.findChildByType(AxonTokenTypes.DOT)
                ?: return (node.findChildByType(AxonTokenTypes.IDENTIFIER) ?: node.findChildByType(AxonTokenTypes.TYPENAME))?.psi
            var n = dot.treeNext
            while (n != null && (n.elementType == TokenType.WHITE_SPACE || n.elementType in AxonTokenTypes.COMMENTS)) n = n.treeNext
            if (n == null || n.firstChildNode != null || n.elementType == TokenType.ERROR_ELEMENT) return null
            return n.psi
        }

    override fun getReference(): PsiReference? {
        val name = referenceNameElement ?: return null
        return AxonReference(this, name.textRangeInParent)
    }
}
