package co.anbora.labs.fantom.actions

import co.anbora.labs.fantom.ide.actions.CreateNewClassAction
import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.LoggedErrorProcessor.Action
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.lang.reflect.Proxy
import java.util.EnumSet

/**
 * [CreateNewClassAction] without UI: the dialog setup is checked with a recording
 * [CreateFileFromTemplateDialog.Builder], and the "Fantom Class" template creates the file.
 * The protected methods are called by reflection because the action class is final.
 */
class CreateNewClassActionTest : BasePlatformTestCase() {

    fun testActionIsRegistered() {
        assertTrue(ActionManager.getInstance().getAction("Fantom.NewFantomClass") is CreateNewClassAction)
    }

    // Current behaviour: plugin.xml registers the action but adds it to no group, so it is
    // not in the File > New menu. This pins that; flip it when the action gets an add-to-group.
    fun testActionIsNotInNewGroup() {
        val group = ActionManager.getInstance().getAction(IdeActions.GROUP_NEW) as DefaultActionGroup
        val ids = group.getChildActionsOrStubs().map { ActionManager.getInstance().getId(it) }
        assertFalse(ids.contains("Fantom.NewFantomClass"))
    }

    fun testBuildDialogSetsTitleAndKind() {
        val calls = mutableListOf<String>()
        lateinit var builder: CreateFileFromTemplateDialog.Builder
        builder = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(CreateFileFromTemplateDialog.Builder::class.java),
        ) { _, method, args ->
            val shown = args?.map { if (it is String) it else it?.javaClass?.simpleName ?: "null" } ?: emptyList()
            calls += method.name + shown.joinToString(", ", "(", ")")
            if (method.returnType == CreateFileFromTemplateDialog.Builder::class.java) builder else null
        } as CreateFileFromTemplateDialog.Builder

        val dir = myFixture.psiManager.findDirectory(myFixture.tempDirFixture.findOrCreateDir("src"))!!
        val buildDialog = CreateNewClassAction::class.java.getDeclaredMethod(
            "buildDialog", Project::class.java, PsiDirectory::class.java, CreateFileFromTemplateDialog.Builder::class.java,
        )
        buildDialog.isAccessible = true
        buildDialog.invoke(CreateNewClassAction(), project, dir, builder)

        assertEquals(2, calls.size)
        assertEquals("setTitle(Fantom Class)", calls[0])
        assertTrue(calls[1], calls[1].startsWith("addKind(Class file, "))
        assertTrue(calls[1], calls[1].endsWith(", Fantom Class)"))
    }

    fun testActionName() {
        val getActionName = CreateNewClassAction::class.java.getDeclaredMethod(
            "getActionName", PsiDirectory::class.java, String::class.java, String::class.java,
        )
        getActionName.isAccessible = true
        assertEquals("Fantom Class", getActionName.invoke(CreateNewClassAction(), null, "Foo", "Fantom Class"))
    }

    // Current behaviour: the platform logs an error for the template, because plugin.xml has no
    // <internalFileTemplate name="Fantom Class"/>. The error is caught here; the file is still made.
    fun testTemplateCreatesClassFile() {
        val errors = mutableListOf<String>()
        val processor = object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<String>, t: Throwable?): Set<Action> {
                errors += message
                return EnumSet.noneOf(Action::class.java)
            }
        }
        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            val template = FileTemplateManager.getInstance(project).getTemplate("Fantom Class")
            assertEquals("fan", template.extension)
            val dir = myFixture.psiManager.findDirectory(myFixture.tempDirFixture.findOrCreateDir("src"))!!
            val file = CreateFileFromTemplateAction.createFileFromTemplate("Foo", template, dir, null, false)
            assertNotNull(file)
            assertEquals("Foo.fan", file!!.name)
            assertEquals("class Foo {\n\n}", file.text.trimEnd())
        }
        assertTrue(errors.toString(), errors.any { it.contains("Unknown bundled file template: Fantom Class") })
    }
}
