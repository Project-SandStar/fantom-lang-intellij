package co.anbora.labs.fantom.ide.run

import co.anbora.labs.fantom.ide.presentation.FantomPresentation
import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.resolve.FantomScope
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Pure PSI questions shared by the run producers and the run line marker:
 * is this a `static Void main`, a `Test` subclass, a `testXxx` method?
 */
object FantomRunnable {
    fun enclosingClass(element: PsiElement): FantomClassDef? =
        PsiTreeUtil.getParentOfType(element, FantomClassDef::class.java, false)

    fun enclosingMethod(element: PsiElement): FantomMethodDef? =
        PsiTreeUtil.getParentOfType(element, FantomMethodDef::class.java, false)

    /** `static Void main(...)` with any parameter list. */
    fun isMain(method: FantomMethodDef): Boolean =
        method.name == "main" && "static" in FantomPresentation.modifiers(method)

    /** The `main` method declared directly in [cls], or null. */
    fun mainOf(cls: FantomClassDef): FantomMethodDef? =
        FantomScope.ownSlots(cls).filterIsInstance<FantomMethodDef>().firstOrNull { isMain(it) }

    /** A non-static method of a [isTestClass] whose name starts with `test`. */
    fun isTestMethod(method: FantomMethodDef): Boolean {
        val name = method.name ?: return false
        if (!name.startsWith("test") || name == "test") return false
        if ("static" in FantomPresentation.modifiers(method)) return false
        val cls = enclosingClass(method) ?: return false
        return FantomScope.enclosingType(method) == cls && isTestClass(cls)
    }

    /** True when the written type chain of [cls] reaches `Test`. */
    fun isTestClass(cls: FantomClassDef): Boolean {
        val project = cls.project
        val seen = HashSet<String>()
        val queue = ArrayDeque<FantomNamedElement>().apply { add(cls) }
        val canResolve = !DumbService.isDumb(project)
        while (queue.isNotEmpty()) {
            val type = queue.removeFirst()
            val name = type.name ?: continue
            if (!seen.add(name)) continue
            for (superName in FantomScope.superTypeNames(type)) {
                if (superName == "Test") return true
                if (superName in seen || !canResolve) continue
                queue += FantomTypeInference.typeDefinitions(superName, project)
            }
        }
        return false
    }
}
