package co.anbora.labs.fantom.ide.run.test

import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.index.FantomDeclarationKind
import co.anbora.labs.fantom.lang.library.FantomPodResolver
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope

/**
 * Maps `fantom://pod::Type` and `fantom://pod::Type.method` to the PSI declaration,
 * so a double-click in the test tree opens the test.
 */
object FantomTestLocator : SMTestLocator {
    const val PROTOCOL = "fantom"

    /** The location URL for `pod::Type` or `pod::Type.method`. */
    fun url(qname: String): String = "$PROTOCOL://$qname"

    /** `pod::Type.method` from a location URL, or null when it is not a Fantom one. */
    fun qnameOf(url: String?): String? {
        if (url == null || !url.startsWith("$PROTOCOL://")) return null
        return url.substring(PROTOCOL.length + 3).takeIf { "::" in it }
    }

    /** Splits `pod::Type[.method]` into its three parts; null when there is no `::`. */
    fun split(qname: String): Triple<String, String, String?>? {
        val sep = qname.indexOf("::")
        if (sep <= 0) return null
        val pod = qname.substring(0, sep)
        val rest = qname.substring(sep + 2)
        val dot = rest.indexOf('.')
        return if (dot < 0) Triple(pod, rest, null) else Triple(pod, rest.substring(0, dot), rest.substring(dot + 1))
    }

    override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
        if (protocol != PROTOCOL) return emptyList()
        val elements = find(path, project, scope).ifEmpty { find(path, project, GlobalSearchScope.allScope(project)) }
        return elements.map { PsiLocation.fromPsiElement(project, it) }
    }

    /** The declarations a `pod::Type[.method]` path names. */
    fun find(path: String, project: Project, scope: GlobalSearchScope): List<FantomNamedElement> {
        val (pod, type, method) = split(path) ?: return emptyList()
        fun inPod(e: FantomNamedElement): Boolean {
            val vf = e.containingFile?.virtualFile ?: return true
            val name = FantomPodResolver.podNameFor(vf, project) ?: return true
            return name == pod
        }
        return if (method == null) {
            FantomDeclarationIndex.findDeclarations(type, project, scope) { it.isType }.filter(::inPod)
        } else {
            FantomDeclarationIndex.findDeclarations(method, project, scope) { it == FantomDeclarationKind.METHOD }
                .filter { FantomDeclarationIndex.enclosingTypeName(it) == type && inPod(it) }
        }
    }
}
