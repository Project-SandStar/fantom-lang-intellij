package co.anbora.labs.fantom.ide.navigation

import co.anbora.labs.fantom.lang.FantomLanguage
import co.anbora.labs.fantom.lang.types.FanType
import co.anbora.labs.fantom.lang.types.FantomTypeAt
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.codeInsight.navigation.actions.TypeDeclarationPlaceAwareProvider
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement

/**
 * Go to Type Declaration (Cmd+Shift+B).
 *
 * First the expression at the caret: its inferred type, so `Bar().me` with
 * `This me()` goes to `Bar`. Without one, the resolved declaration: a local, param,
 * field or method goes to its declared or inferred type. `Str[]` goes to `List`,
 * `[Str:Int]` to `Map`. Type declarations themselves are left alone. The lookup is
 * [FantomTypeAt], which the MCP tool `fantom_type_of` uses too.
 */
class FantomTypeDeclarationProvider : TypeDeclarationPlaceAwareProvider {

    override fun getSymbolTypeDeclarations(symbol: PsiElement, editor: Editor?, offset: Int): Array<PsiElement>? {
        if (!symbol.language.isKindOf(FantomLanguage)) return null
        val atCaret = editor?.let { PsiDocumentManager.getInstance(symbol.project).getPsiFile(it.document) }
            ?.let { FantomTypeAt.expressionAt(it, offset) }
        val type = atCaret ?: FantomTypeAt.declarationType(symbol) ?: return null
        return targets(type, symbol)
    }

    override fun getSymbolTypeDeclarations(symbol: PsiElement): Array<PsiElement>? {
        if (!symbol.language.isKindOf(FantomLanguage)) return null
        return FantomTypeAt.declarationType(symbol)?.let { targets(it, symbol) }
    }

    private fun targets(type: FanType, symbol: PsiElement): Array<PsiElement>? =
        FantomTypeInference.typeDefinitions(typeName(type), symbol.project)
            .takeIf { it.isNotEmpty() }?.toTypedArray()

    private fun typeName(type: FanType): String = when (type) {
        is FanType.Named -> type.name.substringAfterLast("::")
        else -> type.slotOwnerName
    }
}
