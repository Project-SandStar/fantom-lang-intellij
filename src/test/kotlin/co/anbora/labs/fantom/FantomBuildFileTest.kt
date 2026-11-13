package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.library.FantomBuildFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** [FantomBuildFile] reads the literal pieces of a `build.fan` from its PSI. */
class FantomBuildFileTest : BasePlatformTestCase() {

    private fun parse(body: String): FantomBuildFile {
        val file = myFixture.configureByText(
            "build.fan",
            "using build\nclass Build : BuildPod\n{\n  new make()\n  {\n$body\n  }\n}\n",
        )
        return FantomBuildFile.of(file)
    }

    fun testLiteralDepends() {
        val b = parse(
            """
            podName  = "foo"
            version  = Version("1.2.3")
            depends  = ["sys 1.0", "concurrent 1.0+", "web 1.0-2.0"]
            """.trimIndent()
        )
        assertEquals("foo", b.podName)
        assertEquals("1.2.3", b.version)
        assertEquals(
            listOf(
                FantomBuildFile.Dependency("sys", "1.0"),
                FantomBuildFile.Dependency("concurrent", "1.0+"),
                FantomBuildFile.Dependency("web", "1.0-2.0"),
            ),
            b.depends,
        )
        assertEquals(setOf("sys", "concurrent", "web"), b.dependsPods)
    }

    fun testMultiLineDepends() {
        val b = parse("    podName = \"foo\"\n    depends = [\"sys 1.0\",\n               \"util 1.0\"]")
        assertEquals(setOf("sys", "util"), b.dependsPods)
    }

    fun testEmptyDepends() {
        assertEquals(emptyList<FantomBuildFile.Dependency>(), parse("podName = \"foo\"\ndepends = Str[,]").depends)
    }

    fun testMissingDepends() {
        val b = parse("podName = \"foo\"\nsummary = \"x\"")
        assertEquals("foo", b.podName)
        assertNull(b.depends)
        assertNull(b.dependsPods)
        assertNull(b.version)
        assertNull(b.srcDirs)
    }

    fun testNonLiteralDepends() {
        assertNull(parse("podName = \"foo\"\ndepends = deps()").depends)
        assertNull(parse("podName = \"foo\"\ndepends = baseDepends").depends)
        // String interpolation is code, not a literal.
        assertNull(parse("podName = \"foo\"\ndepends = [\"sys \$ver\"]").depends)
        assertNull(parse("podName = \"foo\"\ndepends = [\"sys 1.0\"].addAll(more)").depends)
    }

    fun testSrcDirsAndResDirs() {
        val b = parse("podName = \"foo\"\nsrcDirs = [`fan/`, `test/`]\nresDirs = [`res/img/`]")
        assertEquals(listOf("fan/", "test/"), b.srcDirs)
        assertEquals(listOf("res/img/"), b.resDirs)
    }

    fun testNonLiteralSrcDirs() {
        assertNull(parse("podName = \"foo\"\nsrcDirs = dirs()").srcDirs)
    }

    fun testNonLiteralPodNameAndVersion() {
        val b = parse("podName = name\nversion = config.version")
        assertNull(b.podName)
        assertNull(b.version)
    }

    fun testCacheFollowsEdits() {
        val first = parse("podName = \"foo\"\ndepends = [\"sys 1.0\"]")
        assertSame(first, FantomBuildFile.of(myFixture.file))
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("\"sys"))
        myFixture.type("\"util 1.0\", ")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(setOf("util", "sys"), FantomBuildFile.of(myFixture.file).dependsPods)
    }
}
