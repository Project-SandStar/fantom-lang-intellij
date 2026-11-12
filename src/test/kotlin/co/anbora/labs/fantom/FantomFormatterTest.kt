package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.formatter.FantomLanguageCodeStyleSettingsProvider
import co.anbora.labs.fantom.lang.FantomLanguage
import com.intellij.application.options.CodeStyle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Each case starts from wrongly indented input, so a formatter that does nothing fails. */
class FantomFormatterTest : BasePlatformTestCase() {

    fun testTypeAndSlotIndent() = doFormat(
        """
        class Foo
            {
        Int x := 1
                Void f()
        {
        echo(x)
            }
        }
        """,
        """
        class Foo
        {
          Int x := 1
          Void f()
          {
            echo(x)
          }
        }
        """
    )

    fun testNestedControlBlocks() = doFormat(
        """
        class Foo
        {
          Void f(Int a)
          {
          if (a > 0)
          {
          while (a > 0)
          {
          a--
          }
          }
            else if (a < 0)
          echo("neg")
              else
          echo("zero")
          }
        }
        """,
        """
        class Foo
        {
          Void f(Int a)
          {
            if (a > 0)
            {
              while (a > 0)
              {
                a--
              }
            }
            else if (a < 0)
              echo("neg")
            else
              echo("zero")
          }
        }
        """
    )

    fun testClosureBody() = doFormat(
        """
        class Foo
        {
          Void f()
          {
                list.each |v, i|
                {
            echo(v)
                }
          list.each |v| { echo(v) }
          }
        }
        """,
        """
        class Foo
        {
          Void f()
          {
            list.each |v, i|
            {
              echo(v)
            }
            list.each |v| { echo(v) }
          }
        }
        """
    )

    fun testItBlock() = doFormat(
        """
        class Foo
        {
          Void f()
          {
          t := Foo {
          x = 1
            y = 2
              }
          }
        }
        """,
        """
        class Foo
        {
          Void f()
          {
            t := Foo {
              x = 1
              y = 2
            }
          }
        }
        """
    )

    fun testSwitchAndTry() = doFormat(
        """
        class Foo
        {
          Void f(Int a)
          {
          switch (a)
          {
          case 1:
          case 2:
          echo("one")
          default:
          echo("d")
          }
          try
          {
          a++
          }
          catch (Err e)
          {
          throw e
          }
          finally
          {
          a--
          }
          }
        }
        """,
        """
        class Foo
        {
          Void f(Int a)
          {
            switch (a)
            {
              case 1:
              case 2:
                echo("one")
              default:
                echo("d")
            }
            try
            {
              a++
            }
            catch (Err e)
            {
              throw e
            }
            finally
            {
              a--
            }
          }
        }
        """
    )

    fun testContinuationLinesAndBannersKeepTheirPlace() = doFormat(
        """
        class Foo
        {
        // banner at column 0
          Void f(Int a,
                 Int b)
          {
                s := a > 0 ?
                  "a" :
                  "b"
            x := [1:2,
              3:4]
          }
        }
        """,
        """
        class Foo
        {
        // banner at column 0
          Void f(Int a,
                 Int b)
          {
            s := a > 0 ?
                  "a" :
                  "b"
            x := [1:2,
              3:4]
          }
        }
        """
    )

    fun testInLineSpacingIsKept() = doFormat(
        """
        class Foo
        {
          Void f()
          {
              i:=0
              s   :=  x[0..<n]
              e := [,]
              m := [:]
          }
        }
        """,
        """
        class Foo
        {
          Void f()
          {
            i:=0
            s   :=  x[0..<n]
            e := [,]
            m := [:]
          }
        }
        """
    )

    fun testEnterHandlerAgreesWithFormatter() {
        myFixture.configureByText("A.fan", "class Foo\n{\n  Void f()\n  {<caret>\n}\n")
        myFixture.type('\n')
        myFixture.type("echo(1)")
        val typed = myFixture.editor.document.text
        assertEquals("class Foo\n{\n  Void f()\n  {\n    echo(1)\n}\n", typed)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        reformat()
        assertEquals("class Foo\n{\n  Void f()\n  {\n    echo(1)\n  }\n", myFixture.editor.document.text)
    }

    fun testSettingsProviderBuildsConfigurable() {
        val provider = FantomLanguageCodeStyleSettingsProvider()
        val settings = CodeStyle.getSettings(project)
        val configurable = provider.createConfigurable(settings, settings.clone())
        assertNotNull(configurable)
        assertEquals(2, settings.getIndentOptions(FantomLanguage.associatedFileType).INDENT_SIZE)
        assertTrue(provider.getCodeSample(com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider.SettingsType.INDENT_SETTINGS).contains("class Counter"))
    }

    private fun doFormat(before: String, after: String) {
        myFixture.configureByText("A.fan", before.trimIndent())
        reformat()
        assertEquals(after.trimIndent(), myFixture.editor.document.text)
    }

    private fun reformat() {
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
    }
}
