package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.inspections.FantomUnresolvedReferenceInspection
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomSettings
import co.anbora.labs.fantom.lang.resolve.FantomFfi
import co.anbora.labs.fantom.lang.resolve.FantomReference
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.psi.PsiClass
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.util.indexing.FileBasedIndex
import java.io.File

/**
 * Java FFI: `using [java] pkg::Type` and `using [java] pkg` resolve to the JDK's
 * `PsiClass` (java.base from FantomJdkRootsProvider) and `fanx.interop` to `sys.jar`
 * of the Fantom home; slots on a Java receiver resolve to its methods and fields.
 * The text is the demo pod's `Crypto.fan`. Skips without a Fantom home.
 */
class FantomJavaFfiTest : FantomInspectionTestBase() {

    private var home: File? = null

    override fun setUp() {
        super.setUp()
        home = FantomHome.detect()
        val h = home ?: return
        FantomSettings.getInstance(project).homePath = h.path
        WriteAction.runAndWait<RuntimeException> {
            ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), false, true)
        }
        FileBasedIndex.getInstance().ensureUpToDate(FantomDeclarationIndex.NAME, project, null)
        myFixture.enableInspections(FantomUnresolvedReferenceInspection())
    }

    override fun tearDown() {
        try {
            FantomSettings.getInstance(project).homePath = null
        } finally {
            super.tearDown()
        }
    }

    private fun skip(): Boolean {
        if (home == null || !File(home, "lib/java/sys.jar").isFile) println("FantomJavaFfiTest skipped: no Fantom home with lib/java/sys.jar")
        return home == null || !File(home, "lib/java/sys.jar").isFile
    }

    private val crypto = """
        using [java] java.security
        using [java] javax.crypto
        using [java] javax.crypto.spec::SecretKeySpec
        using [java] javax.crypto.spec::IvParameterSpec
        using [java] java.nio::ByteBuffer
        using [java] java.util::Base64
        using [java] fanx.interop

        const class Crypto {
          private static const Str algorithm := "AES/CBC/PKCS5Padding"

          public static Str encode(Str data, Str iv) {
            Key key := SecretKeySpec(toByteArray(data.toBuf), "AES")
            Cipher c := Cipher.getInstance(algorithm)
            c.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(toByteArray(iv.toBuf)))
            encoded := c.doFinal(toByteArray(data.toBuf))
            alg := key.getAlgorithm()
            echo(alg)
            return Base64.getEncoder().encodeToString(encoded)
          }

          static internal ByteArray toByteArray(Buf buf) {
            bytes := ByteArray.make(buf.size)
            Interop.toJava(buf.in).read(bytes)
            return bytes
          }

          static internal Buf fromByteArray(ByteArray ba) {
            bb := ByteBuffer.wrap(ba)
            return Interop.toFan(bb)
          }
        }
    """.trimIndent()

    private fun targetsAt(marker: String, offsetInMarker: Int = 0): List<com.intellij.psi.PsiElement> {
        val at = myFixture.file.text.indexOf(marker)
        assertTrue("no '$marker'", at >= 0)
        val ref = myFixture.file.findReferenceAt(at + offsetInMarker) as FantomReference
        return ref.multiResolve(false).mapNotNull { it.element }
    }

    fun testCryptoHasNoUnresolvedNames() {
        if (skip()) return
        checkWarnings(crypto)
    }

    fun testImports() {
        if (skip()) return
        myFixture.configureByText("A.fan", crypto)
        val imports = FantomFfi.javaImports(myFixture.file)
        assertEquals("javax.crypto.spec.SecretKeySpec", imports.types["SecretKeySpec"])
        assertEquals(listOf("java.security", "javax.crypto", "fanx.interop"), imports.packages)
    }

    fun testTypesResolveToJavaClasses() {
        if (skip()) return
        myFixture.configureByText("A.fan", crypto)
        assertEquals("javax.crypto.Cipher", (targetsAt("Cipher c").single() as PsiClass).qualifiedName)
        assertEquals("javax.crypto.spec.SecretKeySpec", (targetsAt("SecretKeySpec(").single() as PsiClass).qualifiedName)
        assertEquals("java.util.Base64", (targetsAt("Base64.").single() as PsiClass).qualifiedName)
        assertEquals("fanx.interop.ByteArray", (targetsAt("ByteArray toByteArray").single() as PsiClass).qualifiedName)
        assertEquals("fanx.interop.Interop", (targetsAt("Interop.toJava").single() as PsiClass).qualifiedName)
        assertEquals("java.security.Key", (targetsAt("Key key").single() as PsiClass).qualifiedName)
    }

    fun testSlotsResolveToJavaMembers() {
        if (skip()) return
        myFixture.configureByText("A.fan", crypto)
        val getInstance = targetsAt("getInstance")
        assertTrue(getInstance.isNotEmpty())
        assertTrue(getInstance.all { it is PsiMethod && it.containingClass?.qualifiedName == "javax.crypto.Cipher" })
        assertTrue(targetsAt("doFinal").all { it is PsiMethod && it.name == "doFinal" })
        assertTrue(targetsAt("doFinal").size > 1) // overloads
        assertTrue(targetsAt("ENCRYPT_MODE").single() is PsiField)
        assertEquals("make", (targetsAt("make(buf").single() as PsiMethod).name)
        // `Key` is a Java class here (package import), though some Fantom pod may declare a `Key`.
        assertTrue(targetsAt("getAlgorithm").all { it is PsiMethod && it.name == "getAlgorithm" })
        assertTrue(targetsAt("getAlgorithm").isNotEmpty())
    }

    fun testCompletionAfterJavaReceiver() {
        if (skip()) return
        myFixture.configureByText(
            "A.fan",
            "using [java] javax.crypto\nclass A { Void f() { Cipher c := Cipher.getInstance(\"x\"); c.doF<caret> } }",
        )
        val items = myFixture.completeBasic()?.map { it.lookupString }
        if (items == null) assertTrue(myFixture.editor.document.text, "c.doFinal" in myFixture.editor.document.text)
        else assertTrue(items.toString(), "doFinal" in items)
    }

    fun testStaticCompletionOnJavaType() {
        if (skip()) return
        myFixture.configureByText("A.fan", "using [java] java.util::Base64\nclass A { Void f() { Base64.getEn<caret> } }")
        val items = myFixture.completeBasic()?.map { it.lookupString }
        if (items == null) assertTrue(myFixture.editor.document.text, "Base64.getEncoder" in myFixture.editor.document.text)
        else assertTrue(items.toString(), "getEncoder" in items)
    }

    fun testUnknownJavaNameStillReported() {
        if (skip()) return
        checkWarnings(
            "using [java] java.util::Base64\nclass A { Void f() { Base64.<warning descr=\"Cannot resolve symbol 'zzqNope'\">zzqNope</warning>() } }",
        )
    }

    /** Without the Java plugin there is no resolver: `[java]` names stay unresolved but are not reported. */
    fun testNoWarningsWithoutJavaPlugin() {
        if (skip()) return
        ExtensionTestUtil.maskExtensions(FantomFfi.EP_NAME, emptyList(), testRootDisposable)
        assertNull(FantomFfi.resolver())
        checkWarnings(crypto)
        assertEmpty(targetsAt("Cipher c"))
    }

    fun testFantomNamesStillReportedWithoutJavaPlugin() {
        if (skip()) return
        ExtensionTestUtil.maskExtensions(FantomFfi.EP_NAME, emptyList(), testRootDisposable)
        checkWarnings("using [java] java.util::Base64\nclass A { Void f() { x := <warning descr=\"Cannot resolve symbol 'zzqNope'\">zzqNope</warning>; echo(x) } }")
    }
}
