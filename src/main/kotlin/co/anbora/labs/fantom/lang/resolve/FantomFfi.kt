package co.anbora.labs.fantom.lang.resolve

import co.anbora.labs.fantom.lang.types.FanType
import co.anbora.labs.fantom.lang.types.FantomTypeInference
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

    /**
     * True when the type name [name] in [file] means a Java class, by the same rule as a
     * type reference: a class imported by name wins; with a package import, a Fantom type
     * the file's `using` lines make visible wins, else the Java class when it exists.
     * Without the Java plugin the same rule, with "it may be one" ([mayBeJavaType]) for "it exists".
     */
    fun isJavaType(name: String, file: PsiFile): Boolean {
        val imports = javaImports(file)
        if (imports.isEmpty) return false
        val fan = FantomTypeInference.typeDefinitions(name, file.project)
        val noJava = resolver() == null
        if (name in imports.types) return noJava || resolveType(name, file).isNotEmpty()
        if (fan.any { FantomUsingScope.isVisible(it, file) }) return false
        return if (noJava) mayBeJavaType(name, file) else resolveType(name, file).isNotEmpty()
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

    /** Methods and fields named [slot] on the Java class with the fully qualified name [fqn]. */
    fun resolveSlotOf(fqn: String, slot: String, project: Project): List<PsiElement> {
        val resolver = resolver() ?: return emptyList()
        val classes = resolver.findClasses(listOf(fqn), project)
        if (classes.isEmpty()) return emptyList()
        return resolver.findMembers(classes, slot)
    }

    /** Completion items for the members of the Java class with the fully qualified name [fqn]. */
    fun memberVariantsOf(fqn: String, staticOnly: Boolean, project: Project): List<LookupElement> {
        val resolver = resolver() ?: return emptyList()
        val classes = resolver.findClasses(listOf(fqn), project)
        if (classes.isEmpty()) return emptyList()
        return resolver.memberVariants(classes, staticOnly)
    }

    /** True when [e] is a Java field. */
    fun isJavaField(e: PsiElement): Boolean = resolver()?.signature(e)?.isField == true

    /** The Fantom type of a Java target (class, method, field), or null when it is none. */
    fun typeOf(target: PsiElement): FanType? = resolver()?.typeOf(target)

    /**
     * The Java overloads in [targets] that fit a call with [args] argument types, best first,
     * by the rules of the compiler's `CallResolver`: the argument count must match; a known
     * argument type must fit the parameter type; among the fits, `Int` prefers `long` and
     * `Float` prefers `double`. An unknown argument type (null) fits any parameter.
     * With no [args] list (a field, or no parentheses) every target stays.
     */
    fun pickOverloads(targets: List<PsiElement>, args: List<FanType?>?, project: Project): List<PsiElement> {
        val resolver = resolver() ?: return targets
        if (args == null || targets.size < 2) return targets
        val sigs = targets.map { it to resolver.signature(it) }
        val fits = sigs.filter { (_, sig) ->
            sig != null && !sig.isField && sig.params.size == args.size &&
                sig.params.indices.all { i -> fits(args[i], sig.params[i], resolver, project) }
        }
        if (fits.isEmpty()) return targets
        return fits.sortedByDescending { (_, sig) -> score(args, sig!!) }.map { it.first }
    }

    private fun fits(arg: FanType?, param: FanType?, resolver: FantomFfiResolver, project: Project): Boolean {
        if (arg == null || param == null) return true
        if (param !is FanType.Named) return true
        if (param.name == "Obj") return true
        val paramJava = param.javaClass
        // A closure fits a Java functional interface (or a Fantom `Func` parameter).
        if (arg is FanType.Func) return if (paramJava != null) resolver.isFunctionalInterface(paramJava, project) else param.name == "Func"
        if (arg !is FanType.Named) return paramJava == null
        val argJava = arg.javaClass
        if (paramJava != null) {
            if (paramJava.startsWith("fanx.interop.")) return arg.name == param.name
            if (argJava != null) return resolver.isSubclass(argJava, paramJava, project)
            // A Fantom value passed to a Java class parameter: only `null` (Obj?) can fit.
            return arg.name == "Obj"
        }
        if (argJava != null) return false
        // Both are Fantom types: names must match; Int and Float are distinct.
        return arg.name == param.name || arg.name == "Obj"
    }

    // Higher is better: a parameter of the argument's own type beats `Obj` (the most
    // specific overload wins), and of the primitives the compiler's default widths win.
    private fun score(args: List<FanType?>, sig: JavaSignature): Int =
        args.indices.sumOf { i ->
            val arg = args[i] as? FanType.Named ?: return@sumOf 0
            val param = sig.params[i] as? FanType.Named
            var s = 0
            if (param != null && param.name != "Obj" && param.name == arg.name) s += 2
            s += when (arg.name) {
                "Int" -> if (sig.primitives[i] == "long") 1 else 0
                "Float" -> if (sig.primitives[i] == "double") 1 else 0
                else -> 0
            }
            s
        }
}

/**
 * The Fantom view of a Java member: a field, or a method with its parameter types.
 * [primitives] holds the Java primitive name of each parameter (`long`, `int`, ...) or
 * null, so `Int` can prefer `long` and `Float` can prefer `double` as the compiler does.
 */
data class JavaSignature(
    val isField: Boolean,
    val params: List<FanType?>,
    val primitives: List<String?>,
    val returns: FanType?,
)

/** Finds Java classes and members for [FantomFfi]. Implemented in the Java plugin config only. */
interface FantomFfiResolver {
    /** The classes of these fully qualified names that exist, in order. `Outer$Inner` finds the inner class. */
    fun findClasses(fqns: List<String>, project: Project): List<PsiElement>

    /** Methods (every overload) and fields named [name] on [classes] and their supertypes. */
    fun findMembers(classes: List<PsiElement>, name: String): List<PsiElement>

    /** One completion item per member name of [classes]; only static members when [staticOnly]. */
    fun memberVariants(classes: List<PsiElement>, staticOnly: Boolean): List<LookupElement>

    /**
     * The Fantom type of a Java target: a class is its own (non-nullable) type, a method
     * its return type, a field its type. Primitives map to `Int`, `Float`, `Bool`, `Void`;
     * `Object`, `String`, `BigDecimal` to `Obj?`, `Str?`, `Decimal?`; other objects are nullable.
     */
    fun typeOf(target: PsiElement): FanType?

    /** The signature of a Java method or field, or null for anything else. */
    fun signature(member: PsiElement): JavaSignature?

    /** True when the Java class [sub] is [base] or extends or implements it. */
    fun isSubclass(sub: String, base: String, project: Project): Boolean

    /** True when the Java class [fqn] is an interface with one abstract method (a closure fits it). */
    fun isFunctionalInterface(fqn: String, project: Project): Boolean
}
