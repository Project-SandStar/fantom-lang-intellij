package co.anbora.labs.fantom.ide.debugger.eval

import com.intellij.debugger.DebuggerContext
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.DebuggerUtils
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.ui.tree.DebuggerTreeNode
import com.intellij.debugger.ui.tree.NodeDescriptor
import com.intellij.debugger.ui.tree.ValueDescriptor
import com.intellij.debugger.ui.tree.render.ChildrenBuilder
import com.intellij.debugger.ui.tree.render.ChildrenRenderer
import com.intellij.debugger.ui.tree.render.CompoundRendererProvider
import com.intellij.debugger.ui.tree.render.DescriptorLabelListener
import com.intellij.debugger.ui.tree.render.ReferenceRenderer
import com.intellij.debugger.ui.tree.render.ValueLabelRenderer
import com.intellij.psi.PsiElement
import com.sun.jdi.ArrayReference
import com.sun.jdi.ClassType
import com.sun.jdi.IntegerValue
import com.sun.jdi.ObjectReference
import com.sun.jdi.Type
import com.sun.jdi.Value
import java.util.concurrent.CompletableFuture
import java.util.function.Function

/*
 * Variables-view renderers for Fantom runtime values. Int, Float, Bool and Str are JVM
 * long/double/boolean/String, which the platform already shows as numbers and strings.
 */

/**
 * Base of the label and children renderers. [CompoundReferenceRenderer] asserts that its parts
 * are [ReferenceRenderer]s; it sets their class name itself.
 */
abstract class FantomRendererBase(private val id: String) : ReferenceRenderer() {
    override fun getUniqueId(): String = id
}

/** A label renderer from a function of the value. */
private fun label(id: String, text: (Value, EvaluationContext) -> String): ValueLabelRenderer =
    object : FantomRendererBase(id), ValueLabelRenderer {
        override fun calcLabel(descriptor: ValueDescriptor, context: EvaluationContext, listener: DescriptorLabelListener): String =
            descriptor.value?.let { text(it, context) } ?: "null"
    }

/** The value's Fantom `toStr`, through `FanObj.toString()`. */
private val toStrLabel = label("FantomToStr") { v, ctx -> DebuggerUtils.getValueAsString(ctx, v) }

private const val MAX_ITEMS = 100

/** `fan.sys.List`: label `[a, b, c]`, children the first [MAX_ITEMS] items of `values[0 until size]`. */
class FantomListRendererProvider : CompoundRendererProvider() {
    override fun getName(): String = "Fantom List"
    override fun getClassName(): String = "fan.sys.List"
    override fun isEnabled(): Boolean = true
    override fun getValueLabelRenderer(): ValueLabelRenderer = toStrLabel
    override fun getChildrenRenderer(): ChildrenRenderer = object : FantomRendererBase("FantomListChildren"), ChildrenRenderer {

        override fun buildChildren(value: Value?, builder: ChildrenBuilder, context: EvaluationContext) {
            val list = value as? ObjectReference ?: return
            val type = list.referenceType()
            val size = (list.getValue(type.fieldByName("size")) as? IntegerValue)?.value() ?: 0
            val values = list.getValue(type.fieldByName("values")) as? ArrayReference
            if (values == null || size == 0) {
                builder.addChildren(emptyList(), true)
                return
            }
            val nodes = (0 until minOf(size, MAX_ITEMS)).map { i ->
                builder.nodeManager.createNode(builder.descriptorManager.getArrayItemDescriptor(builder.parentDescriptor, values, i), context)
            }
            builder.addChildren(nodes, true)
        }

        override fun getChildValueExpression(node: DebuggerTreeNode, context: DebuggerContext): PsiElement? = null

        override fun isExpandableAsync(value: Value?, context: EvaluationContext, parent: NodeDescriptor): CompletableFuture<Boolean> {
            val list = value as? ObjectReference ?: return CompletableFuture.completedFuture(false)
            val size = (list.getValue(list.referenceType().fieldByName("size")) as? IntegerValue)?.value() ?: 0
            return CompletableFuture.completedFuture(size > 0)
        }
    }
}

/** `fan.sys.Map`: label `[k:v, ...]`, children the entries of its backing `java.util.HashMap`. */
class FantomMapRendererProvider : CompoundRendererProvider() {
    override fun getName(): String = "Fantom Map"
    override fun getClassName(): String = "fan.sys.Map"
    override fun isEnabled(): Boolean = true
    override fun getValueLabelRenderer(): ValueLabelRenderer = toStrLabel
    override fun getChildrenRenderer(): ChildrenRenderer = object : FantomRendererBase("FantomMapChildren"), ChildrenRenderer {

        private fun inner(value: Value?): ObjectReference? {
            val map = value as? ObjectReference ?: return null
            return map.getValue(map.referenceType().fieldByName("map")) as? ObjectReference
        }

        override fun buildChildren(value: Value?, builder: ChildrenBuilder, context: EvaluationContext) {
            val inner = inner(value)
            val process = context.debugProcess as? DebugProcessImpl
            if (inner == null || process == null) {
                builder.addChildren(emptyList(), true)
                return
            }
            process.getAutoRendererAsync(inner.type()).thenAccept { it.buildChildren(inner, builder, context) }
        }

        override fun getChildValueExpression(node: DebuggerTreeNode, context: DebuggerContext): PsiElement? = null

        override fun isExpandableAsync(value: Value?, context: EvaluationContext, parent: NodeDescriptor): CompletableFuture<Boolean> =
            CompletableFuture.completedFuture(inner(value) != null)
    }
}

/**
 * Other Fantom objects (subclasses of `fan.sys.FanObj` in a `fan.<pod>` package): the label is
 * the Fantom type name, `pod::Type`; the children are the default field view.
 */
class FantomObjectRendererProvider : CompoundRendererProvider() {
    override fun getName(): String = "Fantom Object"
    override fun isEnabled(): Boolean = true

    override fun getIsApplicableChecker(): Function<Type?, CompletableFuture<Boolean>> = Function { type ->
        CompletableFuture.completedFuture(type is ClassType && isFantomObject(type))
    }

    override fun getValueLabelRenderer(): ValueLabelRenderer = label("FantomObjectLabel") { v, _ -> fantomQname(v.type().name()) }

    private fun isFantomObject(type: ClassType): Boolean {
        val name = type.name()
        if (!name.startsWith("fan.") || name == "fan.sys.List" || name == "fan.sys.Map") return false
        var c: ClassType? = type
        while (c != null) {
            if (c.name() == "fan.sys.FanObj") return true
            c = c.superclass()
        }
        return false
    }
}

/** `fan.pod.Type` (or `fan.pod.Type$closure`) as `pod::Type`. */
internal fun fantomQname(className: String): String {
    val parts = className.split('.')
    if (parts.size != 3 || parts[0] != "fan") return className
    return "${parts[1]}::${parts[2]}"
}
