package co.anbora.labs.fantom.lang.axon.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner

/** Every node of the Axon PSI tree. */
interface AxonElement : PsiElement

abstract class AxonElementImpl(node: ASTNode) : ASTWrapperPsiElement(node), AxonElement

/**
 * A declaration with a name: a definition `name: expr` (a local or a top-level function),
 * a lambda parameter, a `defcomp` cell, the variable of `catch (err)`.
 */
interface AxonNamedElement : AxonElement, PsiNameIdentifierOwner

/**
 * A use of a name: `x`, the `f` of `f(a)`, the `f` of `a.f(b)`.
 * Its reference resolves to an [AxonNamedElement] in the same file, see [AxonResolver].
 */
interface AxonNameUse : AxonElement {
    /** The leaf that holds the name. For `a.f(b)` it is the token after the dot. */
    val referenceNameElement: PsiElement?
    val referenceName: String? get() = referenceNameElement?.text
}
