package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.inspections.FantomUndeclaredDependencyInspection
import com.intellij.psi.PsiFile

/** [FantomUndeclaredDependencyInspection] and its "Add to depends" fix. */
class FantomUndeclaredDependencyInspectionTest : FantomInspectionTestBase() {

    override fun setUp() {
        super.setUp()
        FantomUndeclaredDependencyInspection.reset()
        myFixture.enableInspections(FantomUndeclaredDependencyInspection())
    }

    private fun build(depends: String): PsiFile =
        myFixture.addFileToProject(
            "foo/build.fan",
            "using build\nclass Build : BuildPod\n{\n  new make()\n  {\n    podName = \"foo\"\n    $depends\n    srcDirs = [`fan/`]\n  }\n}\n",
        )

    /** Puts [text] with `<warning>` marks at `foo/fan/A.fan` and checks its warnings. */
    private fun highlight(text: String) = checkWarnings(text, "foo/fan/A.fan")

    fun testUndeclaredPodIsReported() {
        build("depends = [\"sys 1.0\", \"concurrent 1.0\"]")
        highlight(
            "using sys\nusing concurrent\nusing <warning descr=\"Pod 'zzweb' is not in the depends of build.fan\">zzweb</warning>::Zz\nusing foo\nclass A {}",
        )
    }

    fun testWholePodUsingIsReported() {
        build("depends = [\"sys 1.0\"]")
        highlight("using <warning descr=\"Pod 'zzinet' is not in the depends of build.fan\">zzinet</warning>\nclass A {}")
    }

    private fun applyFix(using: String) {
        myFixture.addFileToProject("foo/fan/A.fan", "$using\nclass A {}")
        myFixture.configureFromTempProjectFile("foo/fan/A.fan")
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("zzinet") + 2)
        myFixture.launchAction(myFixture.findSingleIntention("Add 'zzinet 1.0' to depends"))
    }

    fun testFixAddsToSingleLineList() {
        val buildFile = build("depends = [\"sys 1.0\", \"concurrent 1.0\"]")
        applyFix("using zzinet")
        assertEquals(
            "using build\nclass Build : BuildPod\n{\n  new make()\n  {\n    podName = \"foo\"\n" +
                "    depends = [\"sys 1.0\", \"concurrent 1.0\", \"zzinet 1.0\"]\n    srcDirs = [`fan/`]\n  }\n}\n",
            buildFile.text,
        )
        // Fixed: the warning and its fix are gone.
        assertEmpty(myFixture.filterAvailableIntentions("Add 'zzinet"))
    }

    fun testFixAddsToMultiLineList() {
        val buildFile = build("depends = [\"sys 1.0\",\n               \"concurrent 1.0\"]")
        applyFix("using zzinet::Zz")
        assertEquals(
            "using build\nclass Build : BuildPod\n{\n  new make()\n  {\n    podName = \"foo\"\n" +
                "    depends = [\"sys 1.0\",\n               \"concurrent 1.0\",\n               \"zzinet 1.0\"]\n" +
                "    srcDirs = [`fan/`]\n  }\n}\n",
            buildFile.text,
        )
    }

    fun testFixFillsEmptyList() {
        val buildFile = build("depends = Str[,]")
        applyFix("using zzinet")
        assertTrue(buildFile.text, buildFile.text.contains("    depends = [\"zzinet 1.0\"]\n"))
    }

    fun testNoBuildFileNoWarning() {
        checkWarnings("using zzinet\nclass A {}")
    }

    fun testNonLiteralDependsNoWarning() {
        build("depends = baseDepends()")
        highlight("using zzinet\nclass A {}")
    }

    fun testMissingDependsNoWarning() {
        build("summary = \"x\"")
        highlight("using zzinet\nclass A {}")
    }

    fun testBuildFileItselfNotChecked() {
        build("depends = [\"sys 1.0\"]")
        myFixture.configureFromTempProjectFile("foo/build.fan")
        assertEmpty(myFixture.doHighlighting(com.intellij.lang.annotation.HighlightSeverity.WARNING)
            .filter { it.description?.startsWith("Pod '") == true })
    }
}
