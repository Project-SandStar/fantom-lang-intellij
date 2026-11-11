package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.refactoring.FantomNamesValidator
import co.anbora.labs.fantom.ide.refactoring.FantomRefactoringSupportProvider
import co.anbora.labs.fantom.ide.usages.FantomFindUsagesProvider
import co.anbora.labs.fantom.lang.FantomLanguage
import com.intellij.lang.LanguageNamesValidation
import com.intellij.lang.findUsages.LanguageFindUsages
import com.intellij.lang.LanguageRefactoringSupport
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Rename on declarations. There are no references yet, so only the
 * declaration itself changes.
 */
class FantomRenameTest : BasePlatformTestCase() {

    fun testRenameField() {
        myFixture.configureByText("Foo.fan", "class Foo { Int b<caret>ar := 1 }")
        myFixture.renameElementAtCaret("baz")
        assertEquals("class Foo { Int baz := 1 }", myFixture.file.text)
    }

    fun testRenameClass() {
        myFixture.configureByText("Foo.fan", "class F<caret>oo { Int bar := 1 }")
        myFixture.renameElementAtCaret("Qux")
        assertEquals("class Qux { Int bar := 1 }", myFixture.file.text)
    }

    fun testExtensionsAreRegistered() {
        assertInstanceOf(LanguageFindUsages.INSTANCE.forLanguage(FantomLanguage), FantomFindUsagesProvider::class.java)
        assertInstanceOf(LanguageNamesValidation.INSTANCE.forLanguage(FantomLanguage), FantomNamesValidator::class.java)
        assertInstanceOf(
            LanguageRefactoringSupport.getInstance().forLanguage(FantomLanguage),
            FantomRefactoringSupportProvider::class.java,
        )
    }
}
