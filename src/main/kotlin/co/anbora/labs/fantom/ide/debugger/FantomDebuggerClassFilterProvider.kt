package co.anbora.labs.fantom.ide.debugger

import com.intellij.ui.classFilter.ClassFilter
import com.intellij.ui.classFilter.DebuggerClassFilterProvider

/**
 * Step Into skips the Fantom runtime: `fan.sys` (the Java runtime of the `sys` pod, no `.fan`
 * sources) and `fanx` (launcher, emitter, serializer). A step from `list.each |x| {...}` goes
 * through `fan.sys.List` and lands in the closure body.
 */
class FantomDebuggerClassFilterProvider : DebuggerClassFilterProvider {
    override fun getFilters(): List<ClassFilter> = FILTERS

    private companion object {
        val FILTERS = listOf(ClassFilter("fan.sys.*"), ClassFilter("fanx.*"))
    }
}
