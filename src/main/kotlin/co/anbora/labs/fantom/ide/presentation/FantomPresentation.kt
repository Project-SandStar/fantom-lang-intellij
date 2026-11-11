package co.anbora.labs.fantom.ide.presentation

import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomCtorDef
import co.anbora.labs.fantom.lang.core.psi.FantomEnumDef
import co.anbora.labs.fantom.lang.core.psi.FantomEnumValDef
import co.anbora.labs.fantom.lang.core.psi.FantomFacetDef
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomItemParameterList
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomMixinDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomType
import com.intellij.icons.AllIcons
import com.intellij.navigation.ItemPresentation
import javax.swing.Icon

/**
 * One place for how a Fantom declaration looks in the IDE: structure view,
 * Go to Class, Go to Symbol. Text: `name` for types, `name: Type` for fields,
 * `name(Params): Ret` for methods, `new name(Params)` for ctors. Icon per kind.
 */
object FantomPresentation {

    /** Presentation of a named declaration. [location] is the grey text on the right (file, owner). */
    fun of(element: FantomNamedElement, location: String? = null): ItemPresentation =
        Simple(text(element), location, icon(element))

    /** The presentable text alone, for callers that build their own presentation. */
    fun text(element: FantomNamedElement): String {
        val name = element.name ?: "?"
        return when (element) {
            is FantomFieldDef -> "$name: ${typeText(element.type)}"
            is FantomMethodDef -> "$name(${paramTypes(element.itemParameterList)}): ${typeText(element.type)}"
            is FantomCtorDef -> "new $name(${paramTypes(element.itemParameterList)})"
            else -> name
        }
    }

    /** The icon for the declaration kind, or null for kinds without one (params, locals). */
    fun icon(element: FantomNamedElement): Icon? = when (element) {
        is FantomClassDef -> AllIcons.Nodes.Class
        is FantomMixinDef -> AllIcons.Nodes.Interface
        is FantomEnumDef -> AllIcons.Nodes.Enum
        is FantomFacetDef -> AllIcons.Nodes.Annotationtype
        is FantomEnumValDef, is FantomFieldDef -> AllIcons.Nodes.Field
        is FantomCtorDef, is FantomMethodDef -> AllIcons.Nodes.Method
        else -> null
    }

    private fun paramTypes(list: FantomItemParameterList?): String =
        list?.paramsList.orEmpty().mapNotNull { it.param }.joinToString(", ") { typeText(it.type) }

    private fun typeText(type: FantomType?): String = type?.text?.trim() ?: "?"

    /** A plain, immutable presentation. */
    class Simple(
        private val text: String,
        private val location: String?,
        private val icon: Icon?,
    ) : ItemPresentation {
        override fun getPresentableText(): String = text
        override fun getLocationString(): String? = location
        override fun getIcon(unused: Boolean): Icon? = icon
    }
}
