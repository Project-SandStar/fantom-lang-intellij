package co.anbora.labs.fantom.lang.resolve

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * Fantom's Java FFI: `using [java] pkg::Type` imports one Java class, `using [java] pkg`
 * a whole package. `fanx.interop` holds `Interop` and the primitive arrays (`ByteArray`,
 * `IntArray`, ...); they live in `<home>/lib/java/sys.jar`.
 *
 * This object reads the `[java]` lines of a file. Finding the Java classes and members
 * needs the Java plugin, so that part is a [FantomFfiResolver] registered in
 * `fantom-java-ffi.xml`; without the Java plugin there is none and every `[java]` name
 * stays unresolved (the unresolved-reference inspection does not report them).
 */
object FantomFfi {

    val EP_NAME: ExtensionPointName<FantomFfiResolver> = ExtensionPointName.create("co.anbora.labs.fantom.ffiResolver")

    /**
     * The `[java]` imports of a file. [types] maps the visible name (the alias when there
     * is one) to the fully qualified class name; [packages] are whole-package imports.
     */
    data class JavaImports(val types: Map<String, String>, val packages: List<String>) {
        val isEmpty: Boolean get() = types.isEmpty() && packages.isEmpty()

        /** Fully qualified candidates for a short name: the by-name import, else one per package. */
        fun candidates(name: String): List<String> =
            types[name]?.let { listOf(it) } ?: packages.map { if (it.isEmpty()) name else "$it.$name" }
    }

    private val NONE = JavaImports(emptyMap(), emptyList())

    fun javaImports(file: PsiFile): JavaImports =
        CachedValuesManager.getCachedValue(file) { CachedValueProvider.Result.create(compute(file), file) }

    private fun compute(file: PsiFile): JavaImports {
        val types = LinkedHashMap<String, String>()
        val packages = ArrayList<String>()
        for (using in FantomUsingScope.usingDefs(file)) {
            val ffi = using.usingPod.podSpec?.ffiPodSpec ?: continue
            val ids = ffi.idList
            if (ids.firstOrNull()?.text != "java") continue
            val pkg = ids.drop(1).joinToString(".") { it.text }
            val type = FantomUsingScope.typeName(using)
            if (type == null) packages += pkg
            else types[FantomUsingScope.aliasName(using) ?: type] = if (pkg.isEmpty()) type else "$pkg.$type"
        }
        return if (types.isEmpty() && packages.isEmpty()) NONE else JavaImports(types, packages)
    }

    /** The FFI resolver of the Java plugin, or null when that plugin is absent. */
    fun resolver(): FantomFfiResolver? = EP_NAME.extensionList.firstOrNull()

    /** True when [name] can be a Java class of [file]: imported by name, or the file imports a Java package. */
    fun mayBeJavaType(name: String, file: PsiFile): Boolean {
        val imports = javaImports(file)
        return name in imports.types || imports.packages.isNotEmpty()
    }

    /** The Java classes [name] can mean in [file]; empty without the Java plugin. */
    fun resolveType(name: String, file: PsiFile): List<PsiElement> {
        val imports = javaImports(file)
        if (imports.isEmpty) return emptyList()
        val resolver = resolver() ?: return emptyList()
        return resolver.findClasses(imports.candidates(name), file.project)
    }

    /** Methods and fields named [slot] on the Java class [typeName] of [file]; overloads all. */
    fun resolveSlot(typeName: String, slot: String, file: PsiFile): List<PsiElement> {
        val classes = resolveType(typeName, file)
        if (classes.isEmpty()) return emptyList()
        return resolver()?.findMembers(classes, slot) ?: emptyList()
    }

    /** Completion items for the members of the Java class [typeName]; [staticOnly] after `Type.`. */
    fun memberVariants(typeName: String, staticOnly: Boolean, file: PsiFile): List<LookupElement> {
        val classes = resolveType(typeName, file)
        if (classes.isEmpty()) return emptyList()
        return resolver()?.memberVariants(classes, staticOnly) ?: emptyList()
    }
}

/** Finds Java classes and members for [FantomFfi]. Implemented in the Java plugin config only. */
interface FantomFfiResolver {
    /** The classes of these fully qualified names that exist, in order. */
    fun findClasses(fqns: List<String>, project: Project): List<PsiElement>

    /** Methods (every overload) and fields named [name] on [classes] and their supertypes. */
    fun findMembers(classes: List<PsiElement>, name: String): List<PsiElement>

    /** One completion item per member name of [classes]; only static members when [staticOnly]. */
    fun memberVariants(classes: List<PsiElement>, staticOnly: Boolean): List<LookupElement>
}
