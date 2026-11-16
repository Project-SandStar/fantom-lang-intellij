package co.anbora.labs.fantom.ide.debugger

import com.intellij.debugger.engine.SuspendContext
import com.intellij.debugger.engine.ExtraSteppingFilter
import com.sun.jdi.request.StepRequest
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.classFilter.DebuggerClassFilterProvider
import java.lang.reflect.Proxy

/**
 * The Step Into filters, without a VM: the class patterns the runtime filter returns,
 * the closure filter's answer for a context with no frame, and the two class and
 * method patterns it matches frames against.
 */
class FantomDebuggerFiltersTest : BasePlatformTestCase() {

    fun testClassFiltersSkipTheRuntime() {
        val filters = FantomDebuggerClassFilterProvider().filters
        assertEquals(listOf("fan.sys.*", "fanx.*"), filters.map { it.pattern })
        assertTrue(filters.all { it.isEnabled })
        assertTrue(filters.any { it.matches("fan.sys.List") })
        assertTrue(filters.any { it.matches("fanx.tools.Fan") })
        assertFalse(filters.any { it.matches("fan.mypod.Foo") })
        assertFalse(filters.any { it.matches("java.lang.String") })
    }

    fun testClassFilterProviderIsRegistered() {
        assertTrue(DebuggerClassFilterProvider.EP_NAME.extensionList.any { it is FantomDebuggerClassFilterProvider })
    }

    /** A context that is not a `SuspendContextImpl` has no frame: the filter stays out. */
    fun testContextWithoutFrameIsNotApplicable() {
        val context = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SuspendContext::class.java)) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                else -> null
            }
        } as SuspendContext
        val filter = FantomClosureSteppingFilter()
        assertFalse(filter.isApplicable(context))
        assertEquals(StepRequest.STEP_INTO, filter.getStepRequestDepth(context))
    }

    private fun privateRegex(name: String): Regex {
        val field = FantomClosureSteppingFilter::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(null) as Regex
    }

    /** `fan.<pod>.<Type>$<method>$<n>` is a closure class; plain types and nested Java classes are not. */
    fun testClosureClassPattern() {
        val closure = privateRegex("CLOSURE_CLASS")
        assertTrue(closure.matches("fan.mypod.Foo\$run\$0"))
        assertTrue(closure.matches("fan.mypod.Foo\$main\$12"))
        assertFalse(closure.matches("fan.mypod.Foo"))
        assertFalse(closure.matches("fan.mypod.Foo\$run"))
        assertFalse(closure.matches("fan.mypod.Foo\$run\$x"))
        assertFalse(closure.matches("java.util.Map\$Entry\$1"))
        assertNull(FantomPositionManager.podOfClass("java.util.Map\$Entry\$1"))
        assertEquals("mypod", FantomPositionManager.podOfClass("fan.mypod.Foo\$run\$0"))
    }

    /** `call`, `call0`..`call9`, `callList` are the bridge; `doCall` and `callOn` are not. */
    fun testCallBridgePattern() {
        val bridge = privateRegex("CALL_BRIDGE")
        for (name in listOf("call", "call0", "call8", "callList")) assertTrue(name, bridge.matches(name))
        for (name in listOf("doCall", "callOn", "call10", "make")) assertFalse(name, bridge.matches(name))
    }

    fun testSteppingFilterIsRegistered() {
        assertTrue(ExtraSteppingFilter.EP_NAME.extensionList.any { it is FantomClosureSteppingFilter })
    }
}
