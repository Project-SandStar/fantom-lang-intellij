package co.anbora.labs.fantom.ide.debugger.eval

import co.anbora.labs.fantom.ide.debugger.eval.FantomEvalRuntime.Companion.NotFound
import co.anbora.labs.fantom.ide.debugger.eval.FantomEvalRuntime.Companion.OPAQUE
import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.sun.jdi.*

/**
 * [FantomEvalRuntime] on the debugged VM, through the JDI calls of the Java debugger.
 *
 * The Fantom runtime (fantom 1.0.84, `src/sys/java`) maps types and slots like this:
 * Int is `long`/`java.lang.Long`, Float `double`/`Double`, Bool `boolean`/`Boolean`, Str
 * `java.lang.String`; their methods are statics on `fan.sys.FanInt`, `FanFloat`, `FanBool`,
 * `FanStr` with `self` first. `List` and `Map` are `fan.sys.List`/`fan.sys.Map`, whose slots
 * `add`, `clear`, `remove`, `size` are `_add`, `_clear`, `_remove`, `_size` in Java
 * (`FanUtil.listSwizzles`). A user type `pod::T` is the class `fan.pod.T`; its fields keep their
 * names. `==` is `fanx.util.OpUtil.compareEQ`, `<=>` is `OpUtil.compare`.
 */
class JdiFantomRuntime(private val ctx: EvaluationContextImpl) : FantomEvalRuntime<Value> {
    private val vm = ctx.virtualMachineProxy
    private val process = ctx.debugProcess
    private val frame = ctx.frameProxy ?: throw FantomEvalException("No stack frame")

    /** The pod of the frame's class: `fan.<pod>.<Type>`. */
    private val pod: String? by lazy {
        frame.location().declaringType().name().split('.').takeIf { it.size >= 3 && it[0] == "fan" }?.get(1)
    }

    private fun <T : Value?> kept(v: T): T {
        if (v is ObjectReference) ctx.keep(v)
        return v
    }

    override fun mirror(value: Any?): Value? = when (value) {
        null -> null
        is Long -> vm.mirrorOf(value)
        is Double -> vm.mirrorOf(value)
        is Boolean -> vm.mirrorOf(value)
        is String -> kept(vm.mirrorOf(value))
        else -> throw FantomEvalException("Cannot make a VM value of $value")
    }

    override fun unwrap(value: Value?): Any? = when (value) {
        null -> null
        is LongValue -> value.value()
        is IntegerValue -> value.value().toLong()
        is ShortValue -> value.value().toLong()
        is ByteValue -> value.value().toLong()
        is CharValue -> value.value().code.toLong()
        is DoubleValue -> value.value()
        is FloatValue -> value.value().toDouble()
        is BooleanValue -> value.value()
        is StringReference -> value.value()
        is ObjectReference -> when (value.referenceType().name()) {
            "java.lang.Long", "java.lang.Integer", "java.lang.Double", "java.lang.Boolean" -> unwrap(boxedValue(value))
            else -> OPAQUE
        }
        else -> OPAQUE
    }

    private fun boxedValue(obj: ObjectReference): Value = obj.getValue(obj.referenceType().fieldByName("value"))

    override fun local(name: String): Any? {
        val variable = try {
            frame.visibleVariableByName(name)
        } catch (_: EvaluateException) {
            null
        } ?: return NotFound
        return frame.getValue(variable)
    }

    override fun thisObject(): Value? {
        val self = frame.thisObject() ?: return null
        // In a closure the JVM `this` is the closure object; Fantom `this` is its `$this` field.
        val outer = self.referenceType().fieldByName("\$this")
        return if (outer != null && !outer.isStatic) self.getValue(outer) else self
    }

    override fun member(target: Value, name: String, args: List<Value?>?): Value? {
        val found = findMember(target, name, args)
        if (found !== NotFound) return found as Value?
        throw FantomEvalException("No slot '$name' on ${fantomTypeName(target)}")
    }

    override fun implicitMember(name: String, args: List<Value?>?): Value? {
        thisObject()?.let { self ->
            val found = findMember(self, name, args)
            if (found !== NotFound) return found as Value?
        }
        val outer = frame.location().declaringType().name().substringBefore('$')
        val type = findClass(outer)
        if (type != null) {
            val found = staticSlot(type, name, args)
            if (found !== NotFound) return found as Value?
        }
        throw FantomEvalException("Unknown name '$name'")
    }

    override fun staticMember(type: String, name: String, args: List<Value?>?): Value? {
        val cls = classCandidates(type, forStatics = true).firstNotNullOfOrNull { findClass(it) }
            ?: throw FantomEvalException("Unknown type '$type' (only loaded classes are found)")
        val found = staticSlot(cls, name, args)
        if (found !== NotFound) return found as Value?
        throw FantomEvalException("No static slot '$name' on $type")
    }

    /** A Value, or [NotFound]. */
    private fun findMember(target: Value, name: String, args: List<Value?>?): Any? {
        val prim = unwrap(target)
        if (prim !== OPAQUE) {
            val cls = when (prim) {
                is Long -> "fan.sys.FanInt"
                is Double -> "fan.sys.FanFloat"
                is Boolean -> "fan.sys.FanBool"
                else -> "fan.sys.FanStr"
            }
            val type = findClass(cls) ?: throw FantomEvalException("Class $cls is not loaded")
            return invokeStatic(type, name, listOf(target) + (args ?: emptyList()))
        }
        val obj = target as ObjectReference
        val type = obj.referenceType()
        val swizzled = when (type.name()) {
            "fan.sys.List" -> listSwizzles[name]
            "fan.sys.Map" -> mapSwizzles[name]
            else -> null
        }
        if (swizzled == null && args == null && type.name() != "fan.sys.List" && type.name() != "fan.sys.Map") {
            type.fieldByName(name)?.let { f ->
                return kept(if (f.isStatic) type.getValue(f) else obj.getValue(f))
            }
        }
        val methodName = swizzled ?: name
        val actual = args ?: emptyList()
        val method = choose(type.methodsByName(methodName).filter { !it.isStatic }, actual)
            ?: return NotFound
        return kept(process.invokeMethod(ctx, obj, method, convert(method, actual)))
    }

    private fun staticSlot(type: ReferenceType, name: String, args: List<Value?>?): Any? {
        if (args == null) {
            type.fieldByName(name)?.takeIf { it.isStatic }?.let { return kept(type.getValue(it)) }
        }
        return invokeStatic(type, name, args ?: emptyList())
    }

    private fun invokeStatic(type: ReferenceType, name: String, args: List<Value?>): Any? {
        val method = choose(type.methodsByName(name).filter { it.isStatic }, args) ?: return NotFound
        val owner = method.declaringType() as? ClassType ?: return NotFound
        return kept(process.invokeMethod(ctx, owner, method, convert(method, args)))
    }

    /** The overload whose parameter types fit [args] best. */
    private fun choose(methods: List<Method>, args: List<Value?>): Method? = methods
        .filter { it.argumentTypeNames().size == args.size }
        .map { m -> m to m.argumentTypeNames().zip(args).map { (p, a) -> score(p, a) } }
        .filter { (_, scores) -> scores.all { it >= 0 } }
        .maxByOrNull { (_, scores) -> scores.sum() }?.first

    private fun score(param: String, arg: Value?): Int {
        val u = unwrap(arg)
        return when (param) {
            "long" -> if (u is Long) 3 else -1
            "int" -> if (u is Long) 2 else -1
            "double" -> if (u is Double) 3 else if (u is Long) 1 else -1
            "boolean" -> if (u is Boolean) 3 else -1
            "java.lang.Object" -> 1
            "java.lang.String" -> if (u == null || u is String) 2 else -1
            "java.lang.Long" -> if (u == null || u is Long) 2 else -1
            "java.lang.Double" -> if (u == null || u is Double) 2 else -1
            "java.lang.Boolean" -> if (u == null || u is Boolean) 2 else -1
            else -> when {
                arg == null -> 1
                arg is ObjectReference && param in superNames(arg.referenceType()) -> 2
                else -> -1
            }
        }
    }

    private fun convert(method: Method, args: List<Value?>): List<Value?> =
        method.argumentTypeNames().zip(args).map { (p, a) ->
            val u = unwrap(a)
            when (p) {
                "long" -> vm.mirrorOf(u as Long)
                "int" -> vm.mirrorOf((u as Long).toInt())
                "double" -> vm.mirrorOf((u as Number).toDouble())
                "boolean" -> vm.mirrorOf(u as Boolean)
                else -> if (a is PrimitiveValue) box(u) else a
            }
        }

    private fun box(value: Any?): Value? {
        val (cls, sig, arg) = when (value) {
            is Long -> Triple("java.lang.Long", "(J)Ljava/lang/Long;", vm.mirrorOf(value))
            is Double -> Triple("java.lang.Double", "(D)Ljava/lang/Double;", vm.mirrorOf(value))
            is Boolean -> Triple("java.lang.Boolean", "(Z)Ljava/lang/Boolean;", vm.mirrorOf(value))
            else -> return mirror(value)
        }
        val type = findClass(cls) as ClassType
        val valueOf = type.concreteMethodByName("valueOf", sig)
        return kept(process.invokeMethod(ctx, type, valueOf, listOf(arg)))
    }

    override fun equals(a: Value?, b: Value?): Boolean =
        unwrap(callOpUtil("compareEQ", a, b)) as Boolean

    override fun compare(a: Value?, b: Value?): Long =
        unwrap(callOpUtil("compare", a, b)) as Long

    private fun callOpUtil(name: String, a: Value?, b: Value?): Value? {
        val type = findClass("fanx.util.OpUtil") as? ClassType ?: throw FantomEvalException("fanx.util.OpUtil is not loaded")
        val method = type.methodsByName(name).first { it.argumentTypeNames() == listOf("java.lang.Object", "java.lang.Object") }
        return process.invokeMethod(ctx, type, method, convert(method, listOf(a, b)))
    }

    override fun toStr(value: Value?): String = when (value) {
        null -> "null"
        is StringReference -> value.value()
        else -> {
            val prim = unwrap(value)
            if (prim !== OPAQUE) prim.toString()
            else {
                val type = findClass("fan.sys.FanObj") as ClassType
                val method = type.methodsByName("toStr").first { it.isStatic && it.argumentTypeNames().size == 1 }
                (process.invokeMethod(ctx, type, method, listOf(value)) as? StringReference)?.value() ?: "null"
            }
        }
    }

    override fun isInstance(value: Value, type: String): Boolean {
        val names = when (value) {
            is PrimitiveValue -> when (unwrap(value)) {
                is Long -> setOf("java.lang.Long", "java.lang.Number", "java.lang.Object")
                is Double -> setOf("java.lang.Double", "java.lang.Number", "java.lang.Object")
                is Boolean -> setOf("java.lang.Boolean", "java.lang.Object")
                else -> emptySet()
            }
            is ObjectReference -> superNames(value.referenceType())
            else -> emptySet()
        }
        return classCandidates(type, forStatics = false).any { it in names }
    }

    override fun newList(items: List<Value?>): Value? {
        val type = findClass("fan.sys.List") ?: throw FantomEvalException("fan.sys.List is not loaded")
        val list = invokeStatic(type, "makeObj", listOf(vm.mirrorOf(items.size.toLong()))) as ObjectReference
        for (item in items) findMember(list, "add", listOf(item))
        return list
    }

    /** The JVM class names a Fantom type name can mean, most likely first. */
    private fun classCandidates(type: String, forStatics: Boolean): List<String> {
        val t = type.removeSuffix("?")
        if (t.endsWith("[]")) return listOf("fan.sys.List")
        if (t.startsWith("[") || (t.contains(':') && !t.contains("::"))) return listOf("fan.sys.Map")
        val podName = t.substringBefore("::", "")
        val name = t.substringAfter("::")
        val sys = sysClasses(name, forStatics)
        return when {
            podName == "sys" -> sys
            podName.isNotEmpty() -> listOf("fan.$podName.$name")
            else -> listOfNotNull(pod?.let { "fan.$it.$name" }) + sys
        }
    }

    private fun sysClasses(name: String, forStatics: Boolean): List<String> {
        val value = mapOf("Int" to "Long", "Float" to "Double", "Str" to "String", "Bool" to "Boolean", "Num" to "Number", "Obj" to "Object")
        return when {
            name in value && forStatics -> listOf("fan.sys.Fan$name")
            name in value -> listOf("java.lang.${value[name]}")
            name == "Decimal" -> listOf(if (forStatics) "fan.sys.FanDecimal" else "java.math.BigDecimal")
            else -> listOf("fan.sys.$name")
        }
    }

    private fun superNames(type: ReferenceType): Set<String> {
        val names = mutableSetOf("java.lang.Object")
        var c: ClassType? = type as? ClassType
        while (c != null) {
            names += c.name()
            c.interfaces().forEach { names += it.name() }
            c.allInterfaces().forEach { names += it.name() }
            c = c.superclass()
        }
        if (type is InterfaceType) names += type.name()
        return names
    }

    private fun findClass(name: String): ReferenceType? =
        vm.classesByName(name).firstOrNull() ?: try {
            process.findClass(ctx, name, ctx.classLoader)
        } catch (_: EvaluateException) {
            null
        }

    /** `fan.pod.Type` as `pod::Type`, for messages. */
    private fun fantomTypeName(v: Value): String {
        val n = v.type().name()
        val parts = n.split('.')
        return if (parts.size == 3 && parts[0] == "fan") "${parts[1]}::${parts[2]}" else n
    }

    companion object {
        private val listSwizzles = mapOf("add" to "_add", "clear" to "_clear", "remove" to "_remove", "size" to "_size")
        private val mapSwizzles = mapOf("clear" to "_clear", "size" to "_size")
    }
}
