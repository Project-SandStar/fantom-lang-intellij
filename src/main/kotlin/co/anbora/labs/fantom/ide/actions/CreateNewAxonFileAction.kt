package co.anbora.labs.fantom.ide.actions

import co.anbora.labs.fantom.ide.icons.FantomIcons
import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory

private const val AXON_CAPTION = "Axon File"

/**
 * File | New | Axon File, also on right-click in the Project view. Two kinds:
 * - "Axon function": a `.axon` file with an empty `() => do ... end`; the file name is the
 *   function name, as in a proj folder (`func/<name>.axon`).
 * - "Axon function record": a `.trio` record with `name`, `doc` and `src`.
 */
class CreateNewAxonFileAction : CreateFileFromTemplateAction(AXON_CAPTION, "Create a new Axon function file", FantomIcons.AXON_FILE) {
    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle("New Axon File")
            .addKind("Axon function", FantomIcons.AXON_FILE, "Axon File")
            .addKind("Axon function record (.trio)", FantomIcons.TRIO_FILE, "Axon Function Record")
            .setValidator(AxonFileNameValidator)
    }

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String = AXON_CAPTION
}

private const val TRIO_CAPTION = "Trio File"

/** File | New | Trio File: a Haystack records file. */
class CreateNewTrioFileAction : CreateFileFromTemplateAction(TRIO_CAPTION, "Create a new Trio records file", FantomIcons.TRIO_FILE) {
    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle("New Trio File")
            .addKind("Trio records", FantomIcons.TRIO_FILE, "Trio File")
            .setValidator(AxonFileNameValidator)
    }

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String = TRIO_CAPTION
}
