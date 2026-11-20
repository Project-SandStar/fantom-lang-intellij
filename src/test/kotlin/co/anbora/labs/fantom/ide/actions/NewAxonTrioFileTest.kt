package co.anbora.labs.fantom.ide.actions

import co.anbora.labs.fantom.ide.icons.FantomIcons
import co.anbora.labs.fantom.lang.axon.AxonFileType
import co.anbora.labs.fantom.lang.trio.TrioFileType
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.ide.fileTemplates.FileTemplateUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.Properties

/** File | New | Axon File and Trio File: the actions, the templates, the icons, the name check. */
class NewAxonTrioFileTest : BasePlatformTestCase() {

    fun testActionsAreRegisteredInTheNewMenu() {
        val am = ActionManager.getInstance()
        assertTrue(am.getAction("Fantom.NewAxonFile") is CreateNewAxonFileAction)
        assertTrue(am.getAction("Fantom.NewTrioFile") is CreateNewTrioFileAction)
    }

    fun testFileTypesHaveTheirOwnIcons() {
        assertSame(FantomIcons.AXON_FILE, AxonFileType.icon)
        assertSame(FantomIcons.TRIO_FILE, TrioFileType.icon)
        assertNotSame(FantomIcons.FILE, FantomIcons.AXON_FILE)
        assertNotSame(FantomIcons.AXON_FILE, FantomIcons.TRIO_FILE)
    }

    private fun create(template: String, name: String): PsiFile {
        val t = FileTemplateManager.getInstance(project).getInternalTemplate(template)
        val dir = myFixture.psiManager.findDirectory(myFixture.tempDirFixture.findOrCreateDir("func"))!!
        val props = Properties(FileTemplateManager.getInstance(project).defaultProperties)
        var file: PsiFile? = null
        WriteCommandAction.runWriteCommandAction(project) {
            file = FileTemplateUtil.createFromTemplate(t, name, props, dir) as PsiFile
        }
        return file!!
    }

    fun testAxonFileTemplateMakesAnEmptyFunction() {
        val f = create("Axon File", "calcDailyAvg")
        assertEquals("calcDailyAvg.axon", f.name)
        assertEquals(AxonFileType, f.fileType)
        assertTrue(f.text, f.text.trimStart().startsWith("() => do"))
        assertTrue(f.text, f.text.trimEnd().endsWith("end"))
    }

    fun testAxonFunctionRecordTemplateNamesTheFunction() {
        val f = create("Axon Function Record", "siteReport")
        assertEquals("siteReport.trio", f.name)
        assertEquals(TrioFileType, f.fileType)
        assertTrue(f.text, f.text.startsWith("name:\"siteReport\""))
        assertTrue(f.text, f.text.contains("src:"))
    }

    fun testTrioFileTemplate() {
        val f = create("Trio File", "plantPoints")
        assertEquals("plantPoints.trio", f.name)
        assertEquals(TrioFileType, f.fileType)
        assertEquals("dis:\"plantPoints\"", f.text.trim())
    }

    fun testNameCheck() {
        val v = AxonFileNameValidator
        for (ok in listOf("calc", "calcDailyAvg", "a_b2", "calc.axon", "rec.trio")) assertTrue(ok, v.checkInput(ok))
        for (bad in listOf("", " ", "2calc", "calc-avg", "my calc", "_x", "a/b")) {
            assertFalse(bad, v.checkInput(bad))
            assertNotNull(bad, v.getErrorText(bad))
        }
    }
}
