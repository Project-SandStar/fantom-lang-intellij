package co.anbora.labs.fantom.ide.navigation

import co.anbora.labs.fantom.lang.FantomLanguage
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.resolve.FantomScope
import co.anbora.labs.fantom.lang.types.FanType
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.codeInsight.navigation.actions.TypeDeclarationProvider
import com.intellij.psi.PsiElement

/**
 * Go to Type Declaration (Cmd+Shift+B). The symbol is what the caret resolves to:
 * a local, param, field or method goes to the type of its declared or inferred type
 * (`List` for `Str[]`, `Map` for `[Str:Int]`). Other Fantom elements go to the
 * inferred type of the expression. Type declarations themselves are left alone.
 */
class FantomTypeDeclarationProvider : TypeDeclarationProvider {

    override fun getSymbolTypeDeclarations(symbol: PsiElement): Array<PsiElement>? {
        if (!symbol.language.isKindOf(FantomLanguage)) return null
        if (FantomScope.isTypeDef(symbol)) return null
        val type = typeOf(symbol) ?: return null
        val targets = FantomTypeInference.typeDefinitions(typeName(type), symbol.project)
        return targets.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private fun typeOf(symbol: PsiElement): FanType? =
        if (symbol is FantomNamedElement) FantomTypeInference.typeOfDeclaration(symbol)
        else FantomTypeInference.typeOf(symbol)

    private fun typeName(type: FanType): String = when (type) {
        is FanType.Named -> type.name.substringAfterLast("::")
        else -> type.slotOwnerName
    }
}
