package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.AxonFile
import co.anbora.labs.fantom.lang.axon.AxonLexer
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes
import co.anbora.labs.fantom.lang.axon.psi.AxonCatchVar
import co.anbora.labs.fantom.lang.axon.psi.AxonCellDef
import co.anbora.labs.fantom.lang.axon.psi.AxonDefExpr
import co.anbora.labs.fantom.lang.axon.psi.AxonNamedElement
import co.anbora.labs.fantom.lang.axon.psi.AxonParam
import co.anbora.labs.fantom.lang.axon.psi.AxonResolver
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet

/**
 * Find Usages for Axon. The words scanner puts the names of `.axon` files in the word
 * index, so that the usages of a project function (its `.axon` file) are found by the name.
 * A new scanner is built on each call because the lexer keeps state.
 */
class AxonFindUsagesProvider : FindUsagesProvider {

    override fun getWordsScanner(): WordsScanner =
        DefaultWordsScanner(AxonLexer(), TokenSet.create(AxonTokenTypes.IDENTIFIER), AxonTokenTypes.COMMENTS, AxonTokenTypes.STRINGS)

    override fun canFindUsagesFor(psiElement: PsiElement): Boolean = psiElement is AxonNamedElement || psiElement is AxonFile

    override fun getHelpId(psiElement: PsiElement): String? = null

    override fun getType(element: PsiElement): String = when (element) {
        is AxonFile -> "Axon function"
        is AxonParam -> "parameter"
        is AxonCellDef -> "cell"
        is AxonCatchVar -> "catch variable"
        is AxonDefExpr -> if (AxonResolver.isFunction(element)) "function" else "local"
        else -> ""
    }

    override fun getDescriptiveName(element: PsiElement): String = nameOf(element)

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String = nameOf(element)

    private fun nameOf(element: PsiElement): String = when (element) {
        is AxonFile -> element.virtualFile?.nameWithoutExtension ?: element.name
        is AxonNamedElement -> element.name.orEmpty()
        else -> ""
    }
}
