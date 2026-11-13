package co.anbora.labs.fantom.ide.compiler

import co.anbora.labs.fantom.lang.library.FantomBuildFile
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement

/** A "Build pod" gutter icon on the `podName` assignment of a `build.fan`. */
class FantomBuildPodLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.firstChild != null) return null
        val file = element.containingFile ?: return null
        if (file.name != FantomBuildFile.FILE_NAME) return null
        if (element.textRange.startOffset != FantomBuildFile.of(file).podNameOffset) return null
        val vf = file.virtualFile ?: return null
        if (FantomBuildPodAction.podRootOf(vf) == null) return null
        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Actions.Compile,
            { "Build pod" },
            { e, elt -> elt.containingFile?.virtualFile?.let { FantomBuildPodAction.run(elt.project, it, e) } },
            GutterIconRenderer.Alignment.LEFT,
            { "Build pod" },
        )
    }
}
