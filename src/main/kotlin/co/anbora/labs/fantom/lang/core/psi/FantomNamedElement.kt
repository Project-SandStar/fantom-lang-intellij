package co.anbora.labs.fantom.lang.core.psi

import com.intellij.psi.PsiNameIdentifierOwner

/**
 * A declaration that has a name: type, slot, enum value, parameter, local.
 *
 * The name identifier is the IDENTIFIER leaf of the declaring rule's direct `id` child,
 * so the type part of `Str name` is never mistaken for the name.
 * Set on rules through the `mixin(...)` and `implements(...)` patterns in Fantom.bnf.
 */
interface FantomNamedElement : FantomElement, PsiNameIdentifierOwner
