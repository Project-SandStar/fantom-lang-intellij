package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.resolve.FantomReference
import co.anbora.labs.fantom.lang.resolve.FantomUsingScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [FantomUsingScope]: which types a file's `using` lines make visible, and the
 * TYPE-resolve preference in [FantomReference]. Pods come from the folder rule
 * (`<pod>/fan/<Type>.fan`) or from `podName = "x"` in `build.fan`.
 */
class FantomUsingScopeTest : BasePlatformTestCase() {

    private fun usings(text: String) = FantomUsingScope.usingsOf(myFixture.configureByText("A.fan", text))

    private fun resolveAllAtCaret(): List<FantomNamedElement> {
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset) as FantomReference
        return ref.resolveAll()
    }

    fun testSysAlwaysVisible() {
        assertTrue(usings("class A {}").isVisible("Str", "sys"))
    }

    fun testUnknownPodCountsAsVisible() {
        assertTrue(usings("class A {}").isVisible("Foo", null))
    }

    fun testOtherPodNotVisibleWithoutUsing() {
        assertFalse(usings("class A {}").isVisible("Actor", "concurrent"))
    }

    fun testWholePodImport() {
        val u = usings("using concurrent\nclass A {}")
        assertTrue(u.isVisible("Actor", "concurrent"))
        assertTrue(u.isVisible("ActorPool", "concurrent"))
        assertFalse(u.isVisible("Buf", "web"))
    }

    fun testTypeImport() {
        val u = usings("using concurrent::Actor\nclass A {}")
        assertTrue(u.isVisible("Actor", "concurrent"))
        assertFalse(u.isVisible("ActorPool", "concurrent"))
        assertFalse(u.isVisible("Actor", "other"))
    }

    fun testAliasMakesAliasVisibleNotOriginal() {
        val u = usings("using concurrent::Actor as Act\nclass A {}")
        assertTrue(u.isVisible("Act", "concurrent"))
        assertFalse(u.isVisible("Actor", "concurrent"))
        assertEquals(FantomUsingScope.TypeImport("concurrent", "Actor"), u.importFor("Act"))
    }

    fun testFfiAndStringSpecsAreIgnored() {
        val u = usings("using [java] java.util::HashMap\nusing \"some.pod\"\nclass A {}")
        assertTrue(u.wholePods.isEmpty())
        assertTrue(u.typeImports.isEmpty())
    }

    fun testOwnPodFromFolderRule() {
        val foo = myFixture.addFileToProject("mypod/fan/Foo.fan", "class Foo {}")
        val file = myFixture.addFileToProject("mypod/fan/A.fan", "class A {}")
        assertEquals("mypod", FantomUsingScope.podOf(foo))
        assertTrue(FantomUsingScope.usingsOf(file).isVisible("Foo", "mypod"))
        assertFalse(FantomUsingScope.usingsOf(file).isVisible("Foo", "otherpod"))
    }

    fun testOwnPodFromBuildFan() {
        myFixture.addFileToProject("proj/build.fan", "class Build : BuildPod {\n  new make() { podName = \"mine\" }\n}")
        val file = myFixture.addFileToProject("proj/src/A.fan", "class A {}")
        assertEquals("mine", FantomUsingScope.podOf(file))
        assertTrue(FantomUsingScope.usingsOf(file).isVisible("Foo", "mine"))
    }

    fun testIsVisibleForDeclaration() {
        val foo = PsiTreeUtil.findChildOfType(myFixture.addFileToProject("a/fan/Foo.fan", "class Foo {}"), FantomClassDef::class.java)!!
        val none = myFixture.configureByText("A.fan", "class A {}")
        assertFalse(FantomUsingScope.isVisible(foo, none))
        val byName = myFixture.configureByText("B.fan", "using a::Foo\nclass B {}")
        assertTrue(FantomUsingScope.isVisible(foo, byName))
        val aliased = myFixture.configureByText("C.fan", "using a::Foo as Bar\nclass C {}")
        assertFalse(FantomUsingScope.isVisible(foo, aliased))
    }

    // ---- FantomReference preference ------------------------------------------

    fun testImportedPodWinsAmongSameShortName() {
        myFixture.addFileToProject("a/fan/Foo.fan", "class Foo {}")
        myFixture.addFileToProject("b/fan/Foo.fan", "class Foo {}")
        myFixture.configureByText("A.fan", "using a::Foo\nclass A { Fo<caret>o? f }")
        val targets = resolveAllAtCaret()
        assertEquals(1, targets.size)
        assertEquals("a", FantomUsingScope.podOf(targets.single()))
    }

    fun testWholePodImportWinsAmongSameShortName() {
        myFixture.addFileToProject("a/fan/Foo.fan", "class Foo {}")
        myFixture.addFileToProject("b/fan/Foo.fan", "class Foo {}")
        myFixture.configureByText("A.fan", "using b\nclass A { Fo<caret>o? f }")
        assertEquals("b", FantomUsingScope.podOf(resolveAllAtCaret().single()))
    }

    fun testNoVisibleCandidateKeepsAll() {
        myFixture.addFileToProject("a/fan/Foo.fan", "class Foo {}")
        myFixture.addFileToProject("b/fan/Foo.fan", "class Foo {}")
        myFixture.configureByText("A.fan", "class A { Fo<caret>o? f }")
        assertEquals(2, resolveAllAtCaret().size)
    }

    fun testStrResolvesWithoutUsings() {
        FantomTestLibrary.with(project) {
            myFixture.configureByText("A.fan", "class A { St<caret>r? s }")
            val targets = resolveAllAtCaret()
            assertFalse("Str did not resolve", targets.isEmpty())
            assertEquals("sys", FantomUsingScope.podOf(targets.first()))
        }
    }

    fun testActorResolvesWithoutUsingsAsBefore() {
        FantomTestLibrary.with(project) {
            myFixture.configureByText("A.fan", "class A { Act<caret>or? a }")
            assertFalse(resolveAllAtCaret().isEmpty())
        }
    }
}
