package co.anbora.labs.fantom.ide.structure

import co.anbora.labs.fantom.ide.presentation.FantomPresentation
import co.anbora.labs.fantom.lang.FantomLanguage
import co.anbora.labs.fantom.lang.core.psi.FantomClosure
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.resolve.FantomScope
import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import javax.swing.Icon

/**
 * Breadcrumbs: type > slot > closure. Types and slots use the structure view texts
 * ([FantomPresentation.text]); a closure shows its signature, `|Int x->Str|`.
 */
class FantomBreadcrumbsProvider : BreadcrumbsProvider {

    override fun getLanguages(): Array<Language> = arrayOf(FantomLanguage)

    override fun acceptElement(e: PsiElement): Boolean =
        e is FantomClosure || (e is FantomNamedElement && (FantomScope.isTypeDef(e) || FantomScope.isSlot(e)))

    override fun getElementInfo(e: PsiElement): String = when (e) {
        is FantomClosure -> e.closureSig.text.replace(Regex("\\s+"), " ")
        is FantomNamedElement -> FantomPresentation.text(e)
        else -> ""
    }

    override fun getElementIcon(e: PsiElement): Icon? =
        (e as? FantomNamedElement)?.let { FantomPresentation.icon(it) }
}
