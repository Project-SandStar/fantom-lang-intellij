package co.anbora.labs.fantom.ide.compiler

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** [FantomPodRoot.find]: the folder of the nearest `build.fan` that names a pod. */
class FantomPodRootTest : BasePlatformTestCase() {

    private val build = "using build\nclass Build : BuildPod\n{\n  new make() { podName = \"mypod\" }\n}\n"

    fun testFileUnderFanFolder() {
        val buildFile = myFixture.addFileToProject("mypod/build.fan", build)
        val foo = myFixture.addFileToProject("mypod/fan/sub/Foo.fan", "class Foo {}")
        assertEquals(buildFile.virtualFile.parent, FantomPodRoot.find(foo.virtualFile))
    }

    fun testBuildFileItselfAndFolder() {
        val buildFile = myFixture.addFileToProject("mypod/build.fan", build)
        val root = buildFile.virtualFile.parent
        assertEquals(root, FantomPodRoot.find(buildFile.virtualFile))
        assertEquals(root, FantomPodRoot.find(root))
    }

    /** A nearer `build.fan` without `podName` (a group build) is passed over. */
    fun testBuildFileWithoutPodNameIsSkipped() {
        val outer = myFixture.addFileToProject("mypod/build.fan", build)
        myFixture.addFileToProject("mypod/fan/build.fan", "class Build : BuildGroup { new make() { childrenScripts = [,] } }")
        val foo = myFixture.addFileToProject("mypod/fan/Foo.fan", "class Foo {}")
        assertEquals(outer.virtualFile.parent, FantomPodRoot.find(foo.virtualFile))
    }

    /** The nearest pod wins over an outer one. */
    fun testNearestPodWins() {
        myFixture.addFileToProject("outer/build.fan", build.replace("mypod", "outer"))
        val inner = myFixture.addFileToProject("outer/inner/build.fan", build.replace("mypod", "inner"))
        val foo = myFixture.addFileToProject("outer/inner/fan/Foo.fan", "class Foo {}")
        assertEquals(inner.virtualFile.parent, FantomPodRoot.find(foo.virtualFile))
    }

    fun testNoBuildFile() {
        val foo = myFixture.addFileToProject("scripts/Hello.fan", "class Hello {}")
        assertNull(FantomPodRoot.find(foo.virtualFile))
    }

    /** `podName` with spaces around `=` still counts. */
    fun testPodNameSpacing() {
        val buildFile = myFixture.addFileToProject("p/build.fan", "class Build : BuildPod { new make() { podName   =  \"p\" } }")
        val foo = myFixture.addFileToProject("p/fan/Foo.fan", "class Foo {}")
        assertEquals(buildFile.virtualFile.parent, FantomPodRoot.find(foo.virtualFile))
    }
}
