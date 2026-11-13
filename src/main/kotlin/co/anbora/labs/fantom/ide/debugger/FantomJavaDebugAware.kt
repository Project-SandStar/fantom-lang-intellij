package co.anbora.labs.fantom.ide.debugger

import co.anbora.labs.fantom.lang.FantomFileType
import com.intellij.debugger.engine.JavaDebugAware
import com.intellij.psi.PsiFile

/** Lets Java line breakpoints be set in `.fan` files. */
class FantomJavaDebugAware : JavaDebugAware() {
    override fun isBreakpointAware(file: PsiFile): Boolean = file.fileType == FantomFileType
}
