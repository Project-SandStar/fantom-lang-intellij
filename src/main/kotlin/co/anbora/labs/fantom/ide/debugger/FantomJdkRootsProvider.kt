package co.anbora.labs.fantom.ide.debugger

import co.anbora.labs.fantom.ide.icons.FantomIcons
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdkType
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.NonClasspathClassFinder
import javax.swing.Icon

/**
 * Gives a Fantom project without a Java SDK the `java.base` classes of the IDE's own runtime,
 * as a class-only library.
 *
 * The platform's List and Map renderers in the Variables view evaluate their expressions
 * (`size()`, `toArray()`, `entrySet()`) in the context of `java.util.List` and `java.util.Map`.
 * `DebuggerUtilsImpl.getPsiClassAndType` finds those classes in `GlobalSearchScope.allScope`,
 * which holds no JDK classes when no module has a Java SDK. The node then shows
 * "Cannot find source class for java.util.List". `fan.sys.List` and `fan.sys.Map` implement
 * those interfaces, so with `java.base` in scope they show their size and their elements.
 *
 * The library makes `java.base` part of `allScope`, but the roots of a synthetic library are no
 * package roots, so `java.lang` has no package and the decompiled `List` cannot resolve its
 * implicit `Object`. [FantomJdkClassFinder] supplies those packages over the same root.
 *
 * When any module, or the project, has a Java SDK, this provider adds nothing.
 */
class FantomJdkRootsProvider : AdditionalLibraryRootsProvider() {

    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val root = javaBaseRoot(project) ?: return emptyList()
        return listOf(JavaBaseLibrary(System.getProperty("java.home"), root))
    }

    /** The `java.base` module of [javaHome], class roots only. Equality is by home and root. */
    class JavaBaseLibrary(private val javaHome: String, private val root: VirtualFile) : SyntheticLibrary(), ItemPresentation {
        override fun getSourceRoots(): Collection<VirtualFile> = emptyList()
        override fun getBinaryRoots(): Collection<VirtualFile> = listOf(root)
        override fun getPresentableText(): String = "java.base (Fantom debugger)"
        override fun getLocationString(): String = javaHome
        override fun getIcon(unused: Boolean): Icon = FantomIcons.FILE
        override fun equals(other: Any?): Boolean = other is JavaBaseLibrary && other.javaHome == javaHome && other.root == root
        override fun hashCode(): Int = 31 * javaHome.hashCode() + root.hashCode()
    }

    companion object {
        /** The `java.base` root of the IDE runtime, or null when the project has a Java SDK. */
        fun javaBaseRoot(project: Project): VirtualFile? {
            if (hasJavaSdk(project)) return null
            val javaHome = System.getProperty("java.home") ?: return null
            return VirtualFileManager.getInstance().findFileByUrl("jrt://$javaHome!/java.base")
        }

        private fun hasJavaSdk(project: Project): Boolean {
            if (ProjectRootManager.getInstance(project).projectSdk?.sdkType is JavaSdkType) return true
            return ModuleManager.getInstance(project).modules.any { ModuleRootManager.getInstance(it).sdk?.sdkType is JavaSdkType }
        }
    }
}

/**
 * The packages and classes of [FantomJdkRootsProvider]'s `java.base` root, for `JavaPsiFacade`.
 * Empty when the project has a Java SDK.
 */
class FantomJdkClassFinder(project: Project) : NonClasspathClassFinder(project, "class") {
    override fun calcClassRoots(): List<VirtualFile> = listOfNotNull(FantomJdkRootsProvider.javaBaseRoot(myProject))
}
