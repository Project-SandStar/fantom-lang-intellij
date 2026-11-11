package co.anbora.labs.fantom.ide.usages

import co.anbora.labs.fantom.lang.core.FANTOM_COMMENTS
import co.anbora.labs.fantom.lang.core.STRING_LITERALS
import co.anbora.labs.fantom.lang.core.psi.FantomCatchDef
import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomClosureParam
import co.anbora.labs.fantom.lang.core.psi.FantomCtorDef
import co.anbora.labs.fantom.lang.core.psi.FantomEnumDef
import co.anbora.labs.fantom.lang.core.psi.FantomEnumValDef
import co.anbora.labs.fantom.lang.core.psi.FantomFacetDef
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomMixinDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomParam
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.IDENTIFIER
import co.anbora.labs.fantom.lang.lexer.FantomLexer
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet

/**
 * Find usages for Fantom named declarations.
 *
 * The words scanner indexes identifiers, comments and string literals.
 * A new scanner is built on each call because the lexer keeps state.
 */
class FantomFindUsagesProvider : FindUsagesProvider {

    override fun getWordsScanner(): WordsScanner =
        DefaultWordsScanner(FantomLexer(), TokenSet.create(IDENTIFIER), FANTOM_COMMENTS, STRING_LITERALS)

    override fun canFindUsagesFor(psiElement: PsiElement): Boolean = psiElement is FantomNamedElement

    override fun getHelpId(psiElement: PsiElement): String? = null

    override fun getType(element: PsiElement): String = when (element) {
        is FantomClassDef -> "class"
        is FantomMixinDef -> "mixin"
        is FantomEnumDef -> "enum"
        is FantomFacetDef -> "facet"
        is FantomEnumValDef -> "enum value"
        is FantomFieldDef -> "field"
        is FantomMethodDef -> "method"
        is FantomCtorDef -> "constructor"
        is FantomParam -> "parameter"
        is FantomLocalDef -> "local variable"
        is FantomClosureParam -> "closure parameter"
        is FantomCatchDef -> "catch parameter"
        else -> ""
    }

    override fun getDescriptiveName(element: PsiElement): String =
        (element as? FantomNamedElement)?.name ?: ""

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String =
        (element as? FantomNamedElement)?.name ?: ""
}
