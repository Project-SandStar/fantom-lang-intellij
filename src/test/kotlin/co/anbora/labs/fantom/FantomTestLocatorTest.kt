package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.run.test.FantomTestLocator
import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** `fantom://pod::Type[.method]` URLs resolve to the declarations in the right pod. */
class FantomTestLocatorTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("src/mypod/build.fan", "using build\nclass Build : BuildPod {\n  new make() { podName = \"mypod\" }\n}\n")
        myFixture.addFileToProject(
            "src/mypod/fan/FooTest.fan",
            "class FooTest : Test {\n  Void testBar() { verify(true) }\n  Void testBaz() { verifyEq(1, 2) }\n}\n",
        )
        myFixture.addFileToProject("src/other/build.fan", "using build\nclass Build : BuildPod {\n  new make() { podName = \"other\" }\n}\n")
        myFixture.addFileToProject("src/other/fan/FooTest.fan", "class FooTest : Test {\n  Void testBar() {}\n}\n")
    }

    private fun locate(url: String) =
        FantomTestLocator.getLocation(
            url.substringBefore("://"), url.substringAfter("://"), project, GlobalSearchScope.allScope(project),
        )

    fun testMethodUrl() {
        val locations = locate(FantomTestLocator.url("mypod::FooTest.testBaz"))
        val method = locations.single().psiElement
        assertTrue(method is FantomMethodDef)
        assertEquals("testBaz", (method as FantomMethodDef).name)
        assertTrue(method.containingFile.virtualFile.path.contains("/mypod/"))
    }

    fun testPodPicksTheRightFile() {
        val method = locate("fantom://other::FooTest.testBar").single().psiElement as FantomMethodDef
        assertTrue(method.containingFile.virtualFile.path.contains("/other/"))
    }

    fun testTypeUrl() {
        val cls = locate("fantom://mypod::FooTest").single().psiElement
        assertTrue(cls is FantomClassDef)
        assertEquals("FooTest", (cls as FantomClassDef).name)
    }

    fun testUnknown() {
        assertEmpty(locate("fantom://mypod::FooTest.testNope"))
        assertEmpty(locate("fantom://mypod::Nope.testBar"))
        assertEmpty(locate("java://mypod::FooTest.testBar"))
    }

    fun testUrlParts() {
        assertEquals("mypod::FooTest.testBar", FantomTestLocator.qnameOf("fantom://mypod::FooTest.testBar"))
        assertNull(FantomTestLocator.qnameOf("file:///x"))
        assertEquals(Triple("mypod", "FooTest", "testBar"), FantomTestLocator.split("mypod::FooTest.testBar"))
        assertEquals(Triple("mypod", "FooTest", null), FantomTestLocator.split("mypod::FooTest"))
    }
}
