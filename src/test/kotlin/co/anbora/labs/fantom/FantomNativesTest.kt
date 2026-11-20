package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.natives.FantomMissingNativeInspection
import co.anbora.labs.fantom.ide.natives.FantomNativeLineMarkerProvider
import co.anbora.labs.fantom.ide.natives.FantomNatives
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import com.intellij.psi.util.PsiTreeUtil

/**
 * Natives: a `native` slot is written in the pod's Java folder (`<Type>Peer.java`) and
 * JS folder (`<Type>Peer.js`). The gutter icon finds them; the inspection reports a slot
 * with no implementation in a folder `build.fan` declares. Needs no Fantom home.
 */
class FantomNativesTest : FantomInspectionTestBase() {

    private val build = "using build\nclass Build : BuildPod { new make() { podName = \"demo\"\n" +
        "javaDirs = [`java/`]\njsDirs = [`js/`] } }\n"

    private fun addPod(js: String = "class ClockPeer { now(self) { return 1; } }") {
        myFixture.addFileToProject("demo/build.fan", build)
        myFixture.addFileToProject(
            "demo/java/ClockPeer.java",
            "package fan.demo; public class ClockPeer { public static long now() { return 1; } public long tick(Clock c) { return 2; } }",
        )
        myFixture.addFileToProject("demo/js/ClockPeer.js", js)
    }

    private val clock = "class Clock {\n  native static Int now()\n  native Int tick()\n  Int plain() { return 0 }\n}\n"

    fun testImplementationsInJavaAndJs() {
        addPod()
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("demo/fan/Clock.fan", clock).virtualFile)
        val methods = PsiTreeUtil.findChildrenOfType(myFixture.file, FantomMethodDef::class.java).associateBy { it.name }
        assertTrue(FantomNatives.isNative(methods["now"]!!))
        assertFalse(FantomNatives.isNative(methods["plain"]!!))
        val java = FantomNatives.implementations(methods["now"]!!, FantomNatives.Kind.JAVA)
        assertEquals("ClockPeer.java", java.single().containingFile.name)
        val js = FantomNatives.implementations(methods["now"]!!, FantomNatives.Kind.JS)
        assertEquals("ClockPeer.js", js.single().containingFile.name)
        assertEmpty(FantomNatives.implementations(methods["tick"]!!, FantomNatives.Kind.JS))
    }

    fun testGutterIconOnNativeSlotOnly() {
        addPod()
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("demo/fan/Clock.fan", clock).virtualFile)
        val provider = FantomNativeLineMarkerProvider()
        fun markerAt(name: String) = provider.getLineMarkerInfo(myFixture.file.findElementAt(myFixture.file.text.indexOf("$name("))!!)
        assertNotNull(markerAt("now"))
        assertNotNull(markerAt("tick"))
        assertNull(markerAt("plain"))
    }

    fun testMissingImplementationReported() {
        addPod()
        myFixture.enableInspections(FantomMissingNativeInspection())
        checkWarnings(
            "class Clock {\n  native static Int now()\n" +
                "  native Int <warning descr=\"Native 'tick' has no method in ClockPeer.js\">tick</warning>()\n" +
                "  Int plain() { return 0 }\n}\n",
            "demo/fan/Clock.fan",
        )
    }

    /** The old JS peer style (`tick = function(self)`, as in dom/js/StoragePeer.js) counts. */
    fun testOldJsFunctionStyleCounts() {
        addPod("fan.demo.ClockPeer.prototype.\$ctor = function(self) {}\nfan.demo.ClockPeer.prototype.now = function(self) { return 1; }\n" +
            "var o = { tick: function(self) { return 2; } };\n")
        myFixture.enableInspections(FantomMissingNativeInspection())
        checkWarnings(clock, "demo/fan/Clock.fan")
    }

    /** A call `.tick(` or a longer name `ticker(` in the peer is not the method itself. */
    fun testCallIsNotAnImplementation() {
        addPod("class ClockPeer { now(self) { return this.tick(self) + ticker(self); } }")
        myFixture.enableInspections(FantomMissingNativeInspection())
        checkWarnings(
            "class Clock {\n  native static Int now()\n" +
                "  native Int <warning descr=\"Native 'tick' has no method in ClockPeer.js\">tick</warning>()\n}\n",
            "demo/fan/Clock.fan",
        )
    }

    /** A type with no peer file in a declared folder is not built for that language: no warning. */
    fun testTypeWithoutPeerFileNotChecked() {
        addPod()
        myFixture.enableInspections(FantomMissingNativeInspection())
        checkWarnings("class Coord {\n  native Float lat()\n}\n", "demo/fan/Coord.fan")
    }

    /** `javaDirs = Uri[,]` (as in the crypto pod) declares no folder, even when java/ exists. */
    fun testEmptyJavaDirsNotChecked() {
        myFixture.addFileToProject(
            "empty/build.fan",
            "using build\nclass Build : BuildPod { new make() { podName = \"empty\"\njavaDirs = Uri[,] } }\n",
        )
        myFixture.addFileToProject("empty/java/ClockPeer.java", "public class ClockPeer { }")
        myFixture.enableInspections(FantomMissingNativeInspection())
        checkWarnings("class Clock {\n  native Int tick()\n}\n", "empty/fan/Clock.fan")
    }

    /** A pod whose build.fan declares no java/js folders is not checked. */
    fun testPodWithoutDeclaredDirsNotChecked() {
        myFixture.addFileToProject("other/build.fan", "using build\nclass Build : BuildPod { new make() { podName = \"other\" } }\n")
        myFixture.enableInspections(FantomMissingNativeInspection())
        checkWarnings("class Clock {\n  native Int tick()\n}\n", "other/fan/Clock.fan")
    }
}
