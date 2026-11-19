package co.anbora.labs.fantom.ide.mcp

import co.anbora.labs.fantom.ide.inspections.FantomNullableReceiverInspection
import co.anbora.labs.fantom.ide.inspections.FantomUnresolvedReferenceInspection
import co.anbora.labs.fantom.ide.inspections.FantomUnusedDeclarationInspection
import co.anbora.labs.fantom.ide.inspections.FantomUnusedUsingInspection
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Each MCP tool must give the answer the matching IDE action gives, on a made-up pod `demo`.
 */
class FantomMcpServiceTest : BasePlatformTestCase() {

    private val build = """
        class Build : build::BuildPod {
          new make() { podName = "demo" }
        }
    """.trimIndent()

    private val foo = """
        class Foo {
          static Void go() {}
          Int count := 0
        }
    """.trimIndent()

    private val bar = """
        class Bar {
          Void m() {
            Foo.go()
            Foo.go()
            x := Foo()
            echo(x.count)
          }
        }
    """.trimIndent()

    private fun pod(): Pair<PsiFile, PsiFile> {
        myFixture.addFileToProject("build.fan", build)
        val a = myFixture.addFileToProject("Foo.fan", foo)
        val b = myFixture.addFileToProject("Bar.fan", bar)
        return a to b
    }

    private fun ok(answer: FantomMcpService.Answer): String {
        assertTrue("expected Ok, got: ${answer.text}", answer is FantomMcpService.Answer.Ok)
        return answer.text
    }

    private fun failText(answer: FantomMcpService.Answer): String {
        assertTrue("expected Fail, got: ${answer.text}", answer is FantomMcpService.Answer.Fail)
        return answer.text
    }

    /** The `path:line:col  text` the tools print for [offset] in [file]. */
    private fun where(file: PsiFile, offset: Int): String {
        val doc = file.viewProvider.document!!
        val line = doc.getLineNumber(offset)
        val col = offset - doc.getLineStartOffset(line) + 1
        val text = doc.getText(com.intellij.openapi.util.TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line))).trim()
        return "${file.name}:${line + 1}:$col  $text"
    }

    private fun nameOffset(e: PsiElement) =
        (e as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange?.startOffset ?: e.textRange.startOffset

    // ---- diagnostics -------------------------------------------------------------------

    /** The problems match the editor highlights at weak warning and above. */
    fun testDiagnosticsMatchEditorHighlighting() {
        myFixture.enableInspections(
            FantomUnresolvedReferenceInspection::class.java,
            FantomUnusedDeclarationInspection::class.java,
            FantomNullableReceiverInspection::class.java,
            FantomUnusedUsingInspection::class.java,
        )
        myFixture.addFileToProject("build.fan", build)
        val file = myFixture.addFileToProject(
            "Baz.fan",
            """
            class Baz {
              private Void unused() {}
              Void m() {
                y := Nope()
                echo(y)
              }
            }
            """.trimIndent()
        )
        myFixture.openFileInEditor(file.virtualFile)
        val doc = myFixture.editor.document
        val expected = myFixture.doHighlighting()
            .filter { it.severity >= HighlightSeverity.WEAK_WARNING && it.description != null }
            .map {
                val line = doc.getLineNumber(it.startOffset)
                "${FantomMcpQueries.severityName(it.severity)} ${line + 1}:${it.startOffset - doc.getLineStartOffset(line) + 1} ${it.description}"
            }
            .sorted()
        assertFalse("the editor must show at least one problem", expected.isEmpty())

        val text = ok(FantomMcpService.diagnostics(project, "Baz.fan", 0))
        val lines = text.lines()
        assertEquals("Baz.fan: ${if (expected.size == 1) "1 problem" else "${expected.size} problems"}", lines[0])
        val actual = lines.drop(1).map { it.substringBeforeLast(" [") }.sorted()
        assertEquals(expected, actual)
    }

    fun testDiagnosticsReportsParseErrors() {
        myFixture.addFileToProject("Broken.fan", "class Broken { Void m( { }")
        val text = ok(FantomMcpService.diagnostics(project, "Broken.fan", 0))
        assertTrue(text, text.lines().drop(1).any { it.startsWith("ERROR ") && it.endsWith("[parser]") })
    }

    fun testDiagnosticsOfAxonFile() {
        myFixture.addFileToProject("f.axon", "(a) => a + 1")
        assertEquals("f.axon: 0 problems", ok(FantomMcpService.diagnostics(project, "f.axon", 0)))
    }

    // ---- resolve -----------------------------------------------------------------------

    /** Resolve at a name gives what the reference there resolves to, as Go to Declaration. */
    fun testResolveAtOffsetMatchesReference() {
        val (a, b) = pod()
        val offset = b.text.indexOf("go()") + 1
        val ideTarget = b.findReferenceAt(offset)!!.resolve()!!
        assertEquals(a, ideTarget.containingFile)

        val text = ok(FantomMcpService.resolve(project, "Bar.fan", offset, 0, 0, "", 0))
        assertEquals("1 target\nmethod demo::Foo.go  ${where(a, nameOffset(ideTarget))}", text)
    }

    fun testResolveByLineAndColumn() {
        val (a, b) = pod()
        val doc = b.viewProvider.document!!
        val offset = b.text.indexOf("count)")
        val line = doc.getLineNumber(offset)
        val column = offset - doc.getLineStartOffset(line) + 1
        val ideTarget = b.findReferenceAt(offset)!!.resolve()!!

        val text = ok(FantomMcpService.resolve(project, "Bar.fan", -1, line + 1, column, "", 0))
        assertEquals("1 target\nfield demo::Foo.count  ${where(a, nameOffset(ideTarget))}", text)
    }

    fun testResolveByQualifiedName() {
        val (a, _) = pod()
        val goOffset = a.text.indexOf("go()")
        assertEquals(
            "1 target\nmethod demo::Foo.go  ${where(a, goOffset)}",
            ok(FantomMcpService.resolve(project, "", -1, 0, 0, "demo::Foo.go", 0)),
        )
        val fooOffset = a.text.indexOf("Foo")
        assertEquals(
            "1 target\nclass demo::Foo  ${where(a, fooOffset)}",
            ok(FantomMcpService.resolve(project, "", -1, 0, 0, "Foo", 0)),
        )
        assertEquals("no declaration found", ok(FantomMcpService.resolve(project, "", -1, 0, 0, "other::Foo", 0)))
        assertTrue(failText(FantomMcpService.resolve(project, "", -1, 0, 0, "not a name!", 0)).startsWith("not a qualified name"))
    }

    fun testResolveAxonLocal() {
        val f = myFixture.addFileToProject(
            "f.axon",
            """
            (a) => do
              total: a + 1
              total * 2
            end
            """.trimIndent()
        )
        val use = f.text.lastIndexOf("total")
        val ideTarget = f.findReferenceAt(use)!!.resolve()!!
        val text = ok(FantomMcpService.resolve(project, "f.axon", use, 0, 0, "", 0))
        assertEquals("1 target\naxon total  ${where(f, nameOffset(ideTarget))}", text)
    }

    // ---- usages ------------------------------------------------------------------------

    /** Usages of a declaration match Find Usages. */
    fun testUsagesMatchFindUsages() {
        val (a, b) = pod()
        val goDecl = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
            a.findElementAt(a.text.indexOf("go()")), PsiNameIdentifierOwner::class.java,
        )!!
        assertEquals("go", goDecl.name)
        val ide = myFixture.findUsages(goDecl).map { u -> where(u.file!!, u.navigationRange!!.startOffset) }.sorted()
        assertEquals(2, ide.size)

        val text = ok(FantomMcpService.usages(project, "Foo.fan", a.text.indexOf("go()"), 0, 0, 0))
        val lines = text.lines()
        assertEquals("usages of method demo::Foo.go: 2", lines[0])
        assertEquals(ide, lines.drop(1).sorted())
        assertTrue(lines.drop(1).all { it.startsWith(b.name) })
    }

    /** At a use, usages are those of the declaration it resolves to. */
    fun testUsagesFromUseSite() {
        val (_, b) = pod()
        val text = ok(FantomMcpService.usages(project, "Bar.fan", b.text.indexOf("go()"), 0, 0, 0))
        assertTrue(text, text.startsWith("usages of method demo::Foo.go: 2"))
    }

    fun testUsagesLimit() {
        pod()
        val text = ok(FantomMcpService.usages(project, "Foo.fan", foo.indexOf("go()"), 0, 0, 1))
        val lines = text.lines()
        assertEquals(3, lines.size)
        assertTrue(lines[2], lines[2].startsWith("... 1 more"))
    }

    fun testAxonUsages() {
        val f = myFixture.addFileToProject(
            "f.axon",
            """
            (a) => do
              total: a + 1
              total * 2
            end
            """.trimIndent()
        )
        val text = ok(FantomMcpService.usages(project, "f.axon", f.text.indexOf("total"), 0, 0, 0))
        assertEquals("usages of axon total: 1\n${where(f, f.text.lastIndexOf("total"))}", text)
    }

    // ---- guards ------------------------------------------------------------------------

    fun testDumbModeAnswersIndexing() {
        pod()
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertEquals(FantomMcpQueries.INDEXING, failText(FantomMcpService.diagnostics(project, "Foo.fan", 0)))
            assertEquals(FantomMcpQueries.INDEXING, failText(FantomMcpService.resolve(project, "", -1, 0, 0, "Foo", 0)))
            assertEquals(FantomMcpQueries.INDEXING, failText(FantomMcpService.usages(project, "Foo.fan", 7, 0, 0, 0)))
        }
    }

    fun testRefusesFilesOutsideTheProject() {
        val outside = java.io.File.createTempFile("outside", ".fan").also { it.writeText("class Secret {}") }
        try {
            val text = failText(FantomMcpService.diagnostics(project, outside.absolutePath, 0))
            assertTrue(text, text.startsWith("not a file in the project") || text.startsWith("file not found"))
            assertFalse(text.contains("Secret"))
        } finally {
            outside.delete()
        }
        assertTrue(failText(FantomMcpService.diagnostics(project, "../x.fan", 0)).startsWith("file not found"))
    }

    fun testRefusesOtherFileTypes() {
        myFixture.addFileToProject("notes.txt", "hello")
        assertTrue(failText(FantomMcpService.diagnostics(project, "notes.txt", 0)).startsWith("not a Fantom or Axon file"))
    }
}
