package co.anbora.labs.fantom.lang.index

import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomCtorDef
import co.anbora.labs.fantom.lang.core.psi.FantomEnumDef
import co.anbora.labs.fantom.lang.core.psi.FantomEnumValDef
import co.anbora.labs.fantom.lang.core.psi.FantomFacetDef
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomMixinDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement

/**
 * What kind of top-level or slot declaration a name belongs to.
 * Locals, params and closure params are not indexed: they are only visible in one body.
 */
enum class FantomDeclarationKind(val isType: Boolean) {
    CLASS(true), MIXIN(true), ENUM(true), FACET(true),
    FIELD(false), METHOD(false), CTOR(false), ENUM_VALUE(false);

    companion object {
        /** The kind for an indexed declaration, or null for locals, params and the like. */
        fun of(element: FantomNamedElement): FantomDeclarationKind? = when (element) {
            is FantomClassDef -> CLASS
            is FantomMixinDef -> MIXIN
            is FantomEnumDef -> ENUM
            is FantomFacetDef -> FACET
            is FantomFieldDef -> FIELD
            is FantomMethodDef -> METHOD
            is FantomCtorDef -> CTOR
            is FantomEnumValDef -> ENUM_VALUE
            else -> null
        }
    }
}
