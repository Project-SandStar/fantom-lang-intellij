package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.AxonLanguage
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.folding.LanguageFolding
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Editor features that use the Axon parser: syntax errors in files and in injected
 * fragments, folding, structure view and the indent formatter.
 */
class AxonEditorTest : BasePlatformTestCase() {

    private fun errors(): List<HighlightInfo> =
        myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR }

    private fun fan(body: String) {
        myFixture.configureByText("A.fan", "class A\n{\n  Void run(Obj cx, Str id)\n  {\n$body\n  }\n}\n")
    }

    // ---- syntax errors -------------------------------------------------------

    fun testCleanFileHasNoError() {
        myFixture.configureByText(
            "a.axon",
            "calcDailyAvg: (point, dates: lastWeek()) => do\n  his: point.hisRead(dates)\n  his.hisRollup(avg, 1day)\nend\n"
        )
        assertEmpty(errors())
    }

    /** One bad token gives one error, and the lines after it are still parsed. */
    fun testOneErrorInFile() {
        val text = "main: () => do\n  a: [1, 2\n  b: 3\n  b + 1\nend\n"
        myFixture.configureByText("a.axon", text)
        val found = errors()
        assertEquals(found.joinToString { it.description }, 1, found.size)
        // The error is at the end of line 2 or at the start of line 3, where `]` is missing.
        assertTrue(found[0].startOffset in text.indexOf("[1, 2")..text.indexOf("b: 3") + 1)
    }

    /** Two separate mistakes give two errors: recovery does not hide the second one. */
    fun testTwoErrorsInFile() {
        myFixture.configureByText("a.axon", "main: () => do\n  a: (1 + 2\n  b: 3\n  c: {x: }\n  d: 4\nend\n")
        assertEquals(2, errors().size)
    }

    /** An error in a complete injected fragment is shown one time, not twice. */
    fun testOneErrorInInjectedFragment() {
        fan("    cx.eval(\"readAll(site\")")
        val found = errors()
        assertEquals(found.joinToString { it.description }, 1, found.size)
    }

    fun testCleanInjectedFragmentHasNoError() {
        fan("    cx.eval(\"readAll(site and area > 100ft²).sort((a, b) => a->dis <=> b->dis)\")")
        assertEmpty(errors())
    }

    /** A string with interpolation is a template: its Axon text is not complete, so no error is shown. */
    fun testNoErrorForInterpolatedString() {
        fan("    cx.eval(\"readById(\$id).\${name}(\")")
        val at = myFixture.file.text.indexOf("readById")
        val injected = InjectedLanguageManager.getInstance(project).findInjectedElementAt(myFixture.file, at + 1)
        assertEquals("the template still gets Axon colours", AxonLanguage, injected?.containingFile?.language)
        assertEmpty(errors())
    }

    /** `\$` is an escaped dollar, not interpolation: the fragment is complete and keeps its error. */
    fun testEscapedDollarIsNotATemplate() {
        assertFalse(AxonInjectedErrorFilter.hasInterpolation("\"cost \\\$ 5\""))
        assertTrue(AxonInjectedErrorFilter.hasInterpolation("\"readById(\$id)\""))
        assertTrue(AxonInjectedErrorFilter.hasInterpolation("\"x \${a.b} y\""))
        fan("    cx.eval(\"format(\\\"\\\$\\\", 5\")")
        assertEquals(1, errors().size)
    }

    /** A trio `src:` value is a complete fragment: one error, shown once. */
    fun testOneErrorInTrioSrc() {
        myFixture.configureByText("lib.trio", "name: calcTotal\nfunc\nsrc:\n  (a, b) => do\n    total: (a + b\n    total\n  end\n")
        val found = errors()
        assertEquals(found.joinToString { it.description }, 1, found.size)
    }

    fun testCleanTrioSrcHasNoError() {
        myFixture.configureByText("lib.trio", "name: calcTotal\nfunc\nsrc:\n  (a, b) => do\n    total: a + b\n    total\n  end\n")
        assertEmpty(errors())
    }

    /** The `src` of a view record holds view settings, not Axon: no injection and no error. */
    fun testTrioViewRecordIsNotAxon() {
        val text = "view: siteTable\nappName: demo\nsrc:\n  view: { inherit:\"table\" }\n  data: { expr:\"readAll(site)\" }\n---\nname: f\nfunc\nsrc: () => now()\n"
        myFixture.configureByText("lib.trio", text)
        val manager = InjectedLanguageManager.getInstance(project)
        assertNull(manager.findInjectedElementAt(myFixture.file, text.indexOf("inherit") + 1))
        assertEquals(AxonLanguage, manager.findInjectedElementAt(myFixture.file, text.indexOf("now") + 1)?.containingFile?.language)
        assertEmpty(errors())
    }

    // ---- folding ---------------------------------------------------------------

    /** "placeholder: first line of the region" for each fold. */
    private fun folds(text: String): List<String> {
        val file = myFixture.configureByText("a.axon", text)
        assertTrue(
            "the Axon folding builder is not registered",
            LanguageFolding.INSTANCE.allForLanguage(AxonLanguage).any { it is AxonFoldingBuilder }
        )
        val builder = AxonFoldingBuilder()
        val document = myFixture.editor.document
        return builder.buildFoldRegions(file, document, false).map {
            assertFalse(builder.isCollapsedByDefault(it.element))
            builder.getPlaceholderText(it.element) + ": " + document.getText(it.range).lineSequence().first()
        }
    }

    fun testFoldDoListDictAndComments() {
        assertEquals(
            listOf(
                "//...: // First line",
                "do...end: do",
                "/*...*/: /* block",
                "[...]: [",
                "{...}: {",
            ),
            folds(
                """
                // First line
                // second line
                main: (a) => do
                  /* block
                     comment */
                  list: [
                    1,
                    2
                  ]
                  tags: {
                    dis: "x"
                  }
                  short: [1, 2]
                  // one line only
                  inner: do a end
                end
                """.trimIndent()
            )
        )
    }

    fun testFoldDefcomp() {
        assertEquals(
            listOf("defcomp...end: defcomp", "do...end: do"),
            folds("defcomp\n  in: {is: ^number}\n  out: {is: ^number}\n  do\n    out = in\n  end\nend\n")
        )
    }

    // ---- structure view ----------------------------------------------------------

    private fun structure(text: String): String {
        val file = myFixture.configureByText("a.axon", text)
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(file)
        assertNotNull("no structure view builder for axon_lang", builder)
        val model = (builder as TreeBasedStructureViewBuilder).createStructureViewModel(myFixture.editor)
        Disposer.register(testRootDisposable, model)
        return dump(model.root, 0)
    }

    private fun dump(element: StructureViewTreeElement, depth: Int): String {
        val line = "  ".repeat(depth) + element.presentation.presentableText + "\n"
        return line + element.children.joinToString("") { dump(it as StructureViewTreeElement, depth + 1) }
    }

    fun testStructureOfFunctions() {
        assertEquals(
            """
            a.axon
              calcDailyAvg(point, dates)
                helper(x)
              toName(rec)
              noArgs()
            """.trimIndent() + "\n",
            structure(
                """
                calcDailyAvg: (point, dates: lastWeek()) => do
                  helper: (x) => x * 2
                  count: 5
                  point.hisRead(dates).map(helper)
                end

                toName: rec => rec->dis
                limit: 100
                noArgs: () => now()
                """.trimIndent()
            )
        )
    }

    /** A trio `src:` value is one lambda with no name: its inner functions show at the top. */
    fun testStructureOfBareLambda() {
        assertEquals(
            "a.axon\n  fmt(v)\n",
            structure("(a, b) => do\n  fmt: (v) => v.toStr\n  fmt(a) + fmt(b)\nend\n")
        )
    }

    fun testStructureOfDefcomp() {
        assertEquals(
            "a.axon\n  gainComp\n    in\n    out\n    scale(v)\n",
            structure("gainComp: defcomp\n  in: {is: ^number}\n  out: {is: ^number}\n  do\n    scale: (v) => v * 2\n    out = scale(in)\n  end\nend\n")
        )
    }

    // ---- formatter -----------------------------------------------------------------

    private fun doFormat(before: String, after: String, name: String = "a.axon") {
        myFixture.configureByText(name, before.trimIndent())
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        assertEquals(after.trimIndent(), myFixture.editor.document.text)
    }

    fun testFormatDoBlocks() = doFormat(
        """
        main: (a, b) => do
        total: a + b
              if (total > 10) do
          echo(total)
                end
            total
          end
        """,
        """
        main: (a, b) => do
          total: a + b
          if (total > 10) do
            echo(total)
          end
          total
        end
        """
    )

    fun testFormatListsDictsAndArgs() = doFormat(
        """
        main: () => do
        tags: {
        dis: "Site",
              area: 100
            }
        list: [
        1,
                2
        ]
        readAll(
        site
            )
        end
        """,
        """
        main: () => do
          tags: {
            dis: "Site",
            area: 100
          }
          list: [
            1,
            2
          ]
          readAll(
            site
          )
        end
        """
    )

    /** Spacing in a line, continuation lines, hand-aligned items and column-0 comments stay as written. */
    fun testFormatKeepsWhatItDoesNotOwn() {
        val text = """
            main: (a,b) => do
            // banner at column 0
              x:a+b   *  2
              y: readAll(site)
                     .sort("dis")
              z: [1, 2,
                  3, 4]
              x
            end
        """.trimIndent()
        doFormat(text, text)
    }

    fun testFormatDefcomp() = doFormat(
        """
        defcomp
        in: {is: ^number}
              out: {is: ^number}
        do
        out = in
        end
        end
        """,
        """
        defcomp
          in: {is: ^number}
          out: {is: ^number}
          do
            out = in
          end
        end
        """
    )

    /** The Axon formatter must not move the lines of a trio `src:` value when the trio file is reformatted. */
    fun testFormatLeavesTrioSrcAlone() {
        val text = "name: f\nfunc\nsrc:\n  (a) => do\n    a + 1\n  end\n"
        myFixture.configureByText("lib.trio", text)
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        assertEquals(text, myFixture.editor.document.text)
    }

    /** Reformat of a Fantom file does not move the lines of an Axon string in it. */
    fun testFormatLeavesFantomStringAlone() {
        val q = "\"\"\""
        val text = "class A\n{\n  Void run(Obj cx)\n  {\n    cx.eval(${q}do\n      a: 1\n          a\n    end$q)\n  }\n}\n"
        myFixture.configureByText("A.fan", text)
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        assertEquals(text, myFixture.editor.document.text)
    }

    /** Types Enter at `<caret>` in [before] and returns the text with `|` at the caret. */
    private fun enter(name: String, before: String): String {
        myFixture.configureByText(name, before)
        myFixture.type('\n')
        val text = myFixture.editor.document.text
        val at = myFixture.caretOffset
        return text.substring(0, at) + "|" + text.substring(at)
    }

    /** Enter in a `do` block or a dict puts the caret one level in. */
    fun testEnterIndents() {
        assertEquals("main: () => do\n  |\nend", enter("a.axon", "main: () => do<caret>\nend"))
        assertEquals("main: () => do\n  x: 1\n  |\nend", enter("b.axon", "main: () => do\n  x: 1<caret>\nend"))
        assertEquals("tags: {\n  |\n}", enter("c.axon", "tags: {<caret>\n}"))
    }
}
