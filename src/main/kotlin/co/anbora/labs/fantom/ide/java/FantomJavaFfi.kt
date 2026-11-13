package co.anbora.labs.fantom.ide.java

import co.anbora.labs.fantom.ide.icons.FantomIcons
import co.anbora.labs.fantom.lang.library.FantomSettings
import co.anbora.labs.fantom.lang.resolve.FantomFfiResolver
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.NonClasspathClassFinder
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import java.io.File
import javax.swing.Icon

/**
 * Java FFI through the Java plugin: classes by `JavaPsiFacade` in the project's whole
 * scope (the JDK, project libraries, `java.base` from the debugger's roots provider when
 * there is no Java SDK, and `sys.jar` from [FantomSysJarRootsProvider]); members by name.
 */
class FantomJavaFfiResolver : FantomFfiResolver {

    override fun findClasses(fqns: List<String>, project: Project): List<PsiElement> {
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        return fqns.mapNotNull { facade.findClass(it, scope) }.distinct()
    }

    override fun findMembers(classes: List<PsiElement>, name: String): List<PsiElement> =
        classes.filterIsInstance<PsiClass>().flatMap { c ->
            c.findMethodsByName(name, true).filter { !it.isConstructor } + listOfNotNull(c.findFieldByName(name, true))
        }.distinct()

    override fun memberVariants(classes: List<PsiElement>, staticOnly: Boolean): List<LookupElement> {
        val seen = HashSet<String>()
        val out = ArrayList<LookupElement>()
        for (c in classes.filterIsInstance<PsiClass>()) {
            val members: List<PsiMember> = c.allMethods.filter { !it.isConstructor } + c.allFields
            for (m in members) {
                if (!m.hasModifierProperty(PsiModifier.PUBLIC)) continue
                if (staticOnly && !m.hasModifierProperty(PsiModifier.STATIC)) continue
                val name = m.name ?: continue
                if (!seen.add(name)) continue
                out += when (m) {
                    is PsiMethod -> LookupElementBuilder.createWithIcon(m)
                        .withTailText(if (m.parameterList.isEmpty) "()" else "(…)", true)
                        .withTypeText(m.returnType?.presentableText)
                    is PsiField -> LookupElementBuilder.createWithIcon(m).withTypeText(m.type.presentableText)
                    else -> LookupElementBuilder.create(name)
                }
            }
        }
        return out
    }
}

/**
 * `<fantom home>/lib/java/sys.jar` as a class-only library, so `fanx.interop::Interop` and
 * the primitive arrays (`ByteArray`, ...) resolve. Taken from the configured home, else from
 * the Fantom sources folder.
 */
class FantomSysJarRootsProvider : AdditionalLibraryRootsProvider() {

    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val root = sysJarRoot(project) ?: return emptyList()
        return listOf(SysJarLibrary(root))
    }

    class SysJarLibrary(private val root: VirtualFile, private val text: String = "sys.jar (Fantom Java FFI)") :
        SyntheticLibrary(), ItemPresentation {
        override fun getSourceRoots(): Collection<VirtualFile> = emptyList()
        override fun getBinaryRoots(): Collection<VirtualFile> = listOf(root)
        override fun getPresentableText(): String = text
        override fun getLocationString(): String = root.path
        override fun getIcon(unused: Boolean): Icon = FantomIcons.FILE
        override fun equals(other: Any?): Boolean = other is SysJarLibrary && other.root == root
        override fun hashCode(): Int = root.hashCode()
    }

    companion object {
        /**
         * The jar root of `lib/java/sys.jar` of the default target (or the single home), else
         * of the Fantom install that supplies `sys`; null when neither has one. Reads the
         * resolved homes, not the legacy `homePath` field: with the install table that field
         * is empty.
         */
        fun sysJarRoot(project: Project): VirtualFile? {
            val settings = FantomSettings.getInstance(project)
            for (dir in listOfNotNull(settings.configuredHome(), settings.effectiveSourcesHome()).distinct()) {
                val jar = File(dir, "lib/java/sys.jar")
                if (!jar.isFile) continue
                val url = "jar://${jar.absolutePath.replace(File.separatorChar, '/')}!/"
                VirtualFileManager.getInstance().findFileByUrl(url)?.let { return it }
            }
            return null
        }
    }
}

/**
 * The packages and classes of `sys.jar` and of pod jars for `JavaPsiFacade`: the roots of a
 * synthetic library are no package roots, so without this finder `fanx.interop` has no package.
 */
class FantomSysJarClassFinder(project: Project) : NonClasspathClassFinder(project, "class") {
    override fun calcClassRoots(): List<VirtualFile> =
        listOfNotNull(FantomSysJarRootsProvider.sysJarRoot(myProject)) + FantomPodJarRootsProvider.podJarRoots(myProject)
}

/**
 * Java libraries a pod bundles: every `.jar` in a pod's `jar/` or `lib/java/` folder, and
 * every jar its `build.fan` names in a backtick URI (`` `lib/foo.jar` ``). A pod is a folder
 * with a `build.fan`, up to three levels below a content root. Its `using [java] pkg::Type`
 * lines resolve against these jars.
 */
class FantomPodJarRootsProvider : AdditionalLibraryRootsProvider() {

    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> =
        podJarRoots(project).map { FantomSysJarRootsProvider.SysJarLibrary(it, "${it.name} (Fantom pod jar)") }

    companion object {
        private const val MAX_DEPTH = 3
        private val SKIP = setOf("build", "out", "node_modules", ".git", ".idea", ".gradle")
        private val JAR_URI = Regex("""`([^`]+\.jar)`""")

        /** The pod folders whose jars are class roots; jars named in `build.fan` count too. */
        val JAR_FOLDERS = listOf("jar", "jars", "lib/java")

        /** The jar roots of every pod in [project]'s content roots. */
        fun podJarRoots(project: Project): List<VirtualFile> {
            if (project.isDisposed) return emptyList()
            val out = LinkedHashSet<VirtualFile>()
            for (root in ProjectRootManager.getInstance(project).contentRoots) collect(root, 0, out)
            return out.toList()
        }

        private fun collect(dir: VirtualFile, depth: Int, out: MutableSet<VirtualFile>) {
            if (!dir.isDirectory) return
            val build = dir.findChild("build.fan")
            if (build != null) {
                for (folder in JAR_FOLDERS) jarsIn(dir.findFileByRelativePath(folder), out)
                namedJars(dir, build, out)
            }
            if (depth >= MAX_DEPTH) return
            for (child in dir.children) {
                if (child.isDirectory && !child.name.startsWith(".") && child.name !in SKIP) collect(child, depth + 1, out)
            }
        }

        private fun jarsIn(dir: VirtualFile?, out: MutableSet<VirtualFile>) {
            if (dir == null || !dir.isDirectory) return
            for (f in dir.children) if (!f.isDirectory && f.extension == "jar") jarRoot(f)?.let(out::add)
        }

        private fun namedJars(pod: VirtualFile, build: VirtualFile, out: MutableSet<VirtualFile>) {
            val text = try { VfsUtilCore.loadText(build) } catch (_: java.io.IOException) { return }
            for (m in JAR_URI.findAll(text)) pod.findFileByRelativePath(m.groupValues[1])?.let { jarRoot(it)?.let(out::add) }
        }

        private fun jarRoot(jar: VirtualFile): VirtualFile? = JarFileSystem.getInstance().getJarRootForLocalFile(jar)
    }
}
