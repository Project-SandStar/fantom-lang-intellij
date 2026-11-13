package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.compiler.FantomCompilerDiagnostics
import co.anbora.labs.fantom.ide.compiler.FantomCompilerService
import co.anbora.labs.fantom.ide.inspections.FantomUnresolvedReferenceInspection
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * [co.anbora.labs.fantom.ide.compiler.FantomCompilerAnnotator] against a real pod on disk,
 * compiled by the Fantom install on this machine. Skips when there is none.
 * The line parser tests need no install.
 */
class FantomCompilerAnnotatorTest : BasePlatformTestCase() {
    private var home: File? = null
    private var podDir: File? = null

    override fun setUp() {
        super.setUp()
        home = FantomHome.detect()
        FantomSettings.getInstance(project).homePath = home?.path
        FantomSettings.getInstance(project).compileOnSave = true
    }

    override fun tearDown() {
        try {
            FantomSettings.getInstance(project).homePath = null
            podDir?.let { FileUtil.delete(it) }
        } finally {
            super.tearDown()
        }
    }

    private fun skip(): Boolean {
        if (home == null) println("FantomCompilerAnnotatorTest skipped: no Fantom home found")
        return home == null
    }

    private fun pod(fooText: String): File {
        val dir = FileUtil.createTempDirectory("fantom-pod", null, true)
        File(dir, "build.fan").writeText(
            """
            using build
            class Build : BuildPod
            {
              new make()
              {
                podName = "ijpodtest"
                summary = "test"
                version = Version("0.1")
                depends = ["sys 1.0"]
                srcDirs = [`fan/`]
              }
            }
            """.trimIndent()
        )
        File(dir, "fan").mkdirs()
        File(dir, "fan/Foo.fan").writeText(fooText)
        File(dir, "fan/Ok.fan").writeText("class Ok { Int n() { return 1 } }\n")
        podDir = dir
        return dir
    }

    private fun highlightFoo(dir: File) = run {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(dir, "fan/Foo.fan"))!!
        myFixture.configureFromExistingVirtualFile(vf)
        myFixture.doHighlighting(HighlightSeverity.WARNING)
    }

    fun testTypoYieldsOneErrorOnItsLine() {
        if (skip()) return
        val dir = pod("class Foo\n{\n  Str name() { return nme }\n}\n")
        val errors = highlightFoo(dir).filter { it.severity == HighlightSeverity.ERROR }
        assertEquals("one error, got ${errors.map { it.description }}", 1, errors.size)
        val err = errors.single()
        assertEquals("Unknown variable 'nme'", err.description)
        assertEquals(2, myFixture.editor.document.getLineNumber(err.startOffset))
        assertEquals("nme", err.text)
        // The resolver inspection stays quiet while the compiler result is fresh.
        myFixture.enableInspections(FantomUnresolvedReferenceInspection())
        val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING)
            .filter { it.description?.startsWith("Cannot resolve") == true }
        assertEmpty(warnings)
        assertTrue(FantomCompilerService.getInstance(project).isFresh(myFixture.file))
    }

    fun testCleanPodYieldsNothing() {
        if (skip()) return
        val dir = pod("class Foo\n{\n  Str name() { return \"n\" }\n}\n")
        val infos = highlightFoo(dir).filter { it.severity == HighlightSeverity.ERROR || it.severity == HighlightSeverity.WARNING }
        assertEmpty(infos)
        val res = FantomCompilerService.getInstance(project).result(dir)!!
        assertTrue(res.completed)
        assertEquals(0, res.exitCode)
        // The pod went into the plugin's temp work folder, not into the install.
        assertFalse(File(home!!, "lib/fan/ijpodtest.pod").exists())
    }

    fun testParseErrorLine() {
        val d = FantomCompilerDiagnostics.parseLine("/tmp/podx/fan/Foo.fan(3,23): Unknown variable 'nme'")!!
        assertEquals(FantomCompilerDiagnostics.canonical("/tmp/podx/fan/Foo.fan"), d.path)
        assertEquals(3, d.line)
        assertEquals(23, d.column)
        assertEquals(HighlightSeverity.ERROR, d.severity)
        assertEquals("Unknown variable 'nme'", d.message)
    }

    fun testParseWarningLine() {
        val d = FantomCompilerDiagnostics.parseLine("/tmp/podx/fan/Foo.fan(5,19): WARN Deprecated slot 'podx::Foo.old'")!!
        assertEquals(5, d.line)
        assertEquals(19, d.column)
        assertEquals(HighlightSeverity.WARNING, d.severity)
        assertEquals("Deprecated slot 'podx::Foo.old'", d.message)
    }

    fun testParseFileLevelLineAndProgressLines() {
        val d = FantomCompilerDiagnostics.parseLine("/tmp/podx/build.fan: Cannot resolve depend: pod 'nopepod' not found")!!
        assertEquals(1, d.line)
        assertEquals(1, d.column)
        assertEquals(HighlightSeverity.ERROR, d.severity)
        assertEquals("Cannot resolve depend: pod 'nopepod' not found", d.message)
        assertNull(FantomCompilerDiagnostics.parseLine("compile [podx]"))
        assertNull(FantomCompilerDiagnostics.parseLine("  Compile [podx]"))
        assertNull(FantomCompilerDiagnostics.parseLine("    FindSourceFiles [2 files]"))
        assertNull(FantomCompilerDiagnostics.parseLine("BUILD FAILED [13ms]!"))
        val grouped = FantomCompilerDiagnostics.parse(
            "compile [podx]\n/tmp/a/Foo.fan(1,2): x\n/tmp/a/Foo.fan(3,4): WARN y\n/tmp/a/Bar.fan(9,9): z\nBUILD FAILED [1ms]!\n"
        )
        assertEquals(2, grouped[FantomCompilerDiagnostics.canonical("/tmp/a/Foo.fan")]!!.size)
        assertEquals(1, grouped[FantomCompilerDiagnostics.canonical("/tmp/a/Bar.fan")]!!.size)
    }
}
