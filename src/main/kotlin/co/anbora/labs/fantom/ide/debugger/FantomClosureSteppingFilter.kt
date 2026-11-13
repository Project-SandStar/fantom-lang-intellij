package co.anbora.labs.fantom.ide.debugger

import com.intellij.debugger.engine.ExtraSteppingFilter
import com.intellij.debugger.engine.SuspendContext
import com.intellij.debugger.engine.SuspendContextImpl
import com.sun.jdi.request.StepRequest

/**
 * Step Into on `list.each |v| {...}` first enters the code that builds the closure object
 * (`fan.<pod>.Type$method$N.make` and its constructor). That code has no Fantom source of its own,
 * so step out of it and go on into the call, which reaches the closure body.
 */
class FantomClosureSteppingFilter : ExtraSteppingFilter {
    override fun isApplicable(context: SuspendContext): Boolean = depth(context) != null

    override fun getStepRequestDepth(context: SuspendContext): Int = depth(context) ?: StepRequest.STEP_INTO

    /**
     * Closure construction (`make`, constructor, static init): step out. The `call`/`callN` bridge
     * that unboxes arguments and calls `doCall`: step into, which reaches the body. Null otherwise.
     */
    private fun depth(context: SuspendContext): Int? {
        val frame = (context as? SuspendContextImpl)?.frameProxy ?: return null
        val location = runCatching { frame.location() }.getOrNull() ?: return null
        val type = location.declaringType().name()
        if (FantomPositionManager.podOfClass(type) == null || !CLOSURE_CLASS.matches(type)) return null
        val method = location.method()
        return when {
            method.isConstructor || method.isStaticInitializer || method.name() == "make" -> StepRequest.STEP_OUT
            CALL_BRIDGE.matches(method.name()) -> StepRequest.STEP_INTO
            else -> null
        }
    }

    private companion object {
        /** `fan.<pod>.<Type>$<method>$<n>`: the class the runtime emits for a closure. */
        val CLOSURE_CLASS = Regex("""fan\.[^.]+\.[^.$]+\$[^$]+\$\d+""")

        /** `call`, `call0` .. `call8`, `callList`: the `fan.sys.Func` entry points of a closure. */
        val CALL_BRIDGE = Regex("""call(\d|List)?""")
    }
}
