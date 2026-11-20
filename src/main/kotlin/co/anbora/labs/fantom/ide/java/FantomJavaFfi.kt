package co.anbora.labs.fantom.ide.java

import co.anbora.labs.fantom.ide.icons.FantomIcons
import co.anbora.labs.fantom.lang.library.FantomSettings
import co.anbora.labs.fantom.lang.resolve.FantomFfiResolver
import co.anbora.labs.fantom.lang.resolve.JavaSignature
import co.anbora.labs.fantom.lang.types.FanType
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
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
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
        // Fantom names an inner class `Outer$Inner`; Java PSI names it `Outer.Inner`.
        return fqns.mapNotNull { fqn ->
            facade.findClass(fqn, scope) ?: fqn.takeIf { '$' in it }?.let { facade.findClass(it.replace('$', '.'), scope) }
        }.distinct()
    }

    override fun typeOf(target: PsiElement): FanType? = when (target) {
        is PsiClass -> classType(target, nullable = false)
        is PsiMethod -> if (target.isConstructor) target.containingClass?.let { classType(it, nullable = false) }
            else target.returnType?.let(::toFan)
        is PsiField -> toFan(target.type)
        else -> null
    }

    override fun signature(member: PsiElement): JavaSignature? = when (member) {
        is PsiMethod -> {
            val params = member.parameterList.parameters
            JavaSignature(
                isField = false,
                params = params.map { toFan(it.type) },
                primitives = params.map { (it.type as? PsiPrimitiveType)?.canonicalText },
                returns = typeOf(member),
            )
        }
        is PsiField -> JavaSignature(isField = true, params = emptyList(), primitives = emptyList(), returns = toFan(member.type))
        else -> null
    }

    override fun isSubclass(sub: String, base: String, project: Project): Boolean {
        if (sub == base) return true
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        val s = facade.findClass(sub, scope) ?: return false
        val b = facade.findClass(base, scope) ?: return false
        return s == b || s.isInheritor(b, true)
    }

    override fun isFunctionalInterface(fqn: String, project: Project): Boolean {
        val c = JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project)) ?: return false
        if (!c.isInterface || c.isAnnotationType) return false
        val abstract = c.allMethods.filter { m ->
            m.hasModifierProperty(PsiModifier.ABSTRACT) && !isObjectMethod(m)
        }.map { it.name to it.parameterList.parametersCount }.toSet()
        return abstract.size == 1
    }

    // `equals`, `hashCode` and `toString` an interface redeclares do not count (`Comparator`).
    private fun isObjectMethod(m: PsiMethod): Boolean = when (m.name) {
        "equals" -> m.parameterList.parametersCount == 1
        "hashCode", "toString" -> m.parameterList.isEmpty
        else -> false
    }

    /**
     * A Java type as Fantom sees it, as the compiler's `JavaReflect.toFanType` maps it:
     * `long`, `int`, `short`, `byte`, `char` are `Int`; `double`, `float` are `Float`;
     * `boolean` is `Bool`; `Object`, `String`, `BigDecimal` are `Obj?`, `Str?`, `Decimal?`;
     * other classes are nullable Java types; primitive arrays are the `fanx.interop` arrays.
     * Object arrays have no Fantom type here (null).
     */
    private fun toFan(t: PsiType): FanType? = when (t) {
        is PsiPrimitiveType -> when (t.canonicalText) {
            "boolean" -> FanType.BOOL
            "long", "int", "short", "byte", "char" -> FanType.INT
            "double", "float" -> FanType.FLOAT
            "void" -> FanType.VOID
            else -> null
        }
        is PsiArrayType -> (t.componentType as? PsiPrimitiveType)?.let { PRIMITIVE_ARRAYS[it.canonicalText] }
            ?.let { FanType.Named(it, nullable = true, javaClass = "fanx.interop.$it") }
        is PsiClassType -> when (val c = t.resolve()) {
            null -> null
            is PsiTypeParameter -> FanType.OBJ.withNullable(true)
            else -> when (c.qualifiedName) {
                "java.lang.Object" -> FanType.OBJ.withNullable(true)
                "java.lang.String" -> FanType.STR.withNullable(true)
                "java.math.BigDecimal" -> FanType.DECIMAL.withNullable(true)
                // The compiler maps `fan.sys.X` to the Fantom type `sys::X?`.
                else -> if (c.qualifiedName?.startsWith("fan.sys.") == true) c.name?.let { FanType.Named(it, nullable = true) }
                    else classType(c, nullable = true)
            }
        }
        else -> null
    }

    /** The Fantom name of [c] (`Outer$Inner` for an inner class) with its Java name. */
    private fun classType(c: PsiClass, nullable: Boolean): FanType? {
        val fqn = c.qualifiedName ?: return null
        var name = c.name ?: return null
        var outer = c.containingClass
        while (outer != null) {
            name = "${outer.name}$$name"
            outer = outer.containingClass
        }
        return FanType.Named(name, nullable, javaClass = fqn)
    }

    private companion object {
        val PRIMITIVE_ARRAYS = mapOf(
            "boolean" to "BooleanArray", "byte" to "ByteArray", "short" to "ShortArray", "char" to "CharArray",
            "int" to "IntArray", "long" to "LongArray", "float" to "FloatArray", "double" to "DoubleArray",
        )
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
