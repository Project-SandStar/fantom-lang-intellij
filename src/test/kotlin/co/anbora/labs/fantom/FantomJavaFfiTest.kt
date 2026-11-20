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
        return !TestInstalls.requireInstall(home?.let { File(it, "lib/java/sys.jar") }, "a Fantom home with lib/java/sys.jar")
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

    /**
     * With the install table (a ticked default target) `homePath` stays empty; sys.jar must
     * still come from the target. The sandbox projects are set up this way.
     */
    fun testSysJarFromDefaultTarget() {
        if (skip()) return
        val settings = FantomSettings.getInstance(project)
        val target = File(System.getProperty("user.home"), "skyspark/skyspark-3.1.12")
        val dir = if (File(target, "lib/java/sys.jar").isFile) target else home!!
        val rows = co.anbora.labs.fantom.lang.library.FantomInstalls.pair(
            co.anbora.labs.fantom.lang.library.FantomInstalls.scan(listOf(dir.parentFile)),
        ).map { it.copy(enabled = it.path == dir.path) }
        if (rows.none { it.enabled }) { println("testSysJarFromDefaultTarget skipped: $dir not scanned"); return }
        try {
            settings.homePath = null
            settings.installs = rows
            settings.defaultTarget = dir.path
            val root = co.anbora.labs.fantom.ide.java.FantomSysJarRootsProvider.sysJarRoot(project)
            assertNotNull("sys.jar of $dir", root)
            assertTrue(root!!.path, root.path.startsWith(dir.path))
        } finally {
            settings.installs = emptyList()
            settings.defaultTarget = null
            settings.homePath = home!!.path
        }
    }

    /** bassgModbusRtu writes `using [java]fanx.interop::ByteArray`, with no space after `]`. */
    fun testNoSpaceAfterJavaBracket() {
        if (skip()) return
        myFixture.enableInspections(FantomUnresolvedReferenceInspection())
        myFixture.configureByText(
            "A.fan",
            "using [java]fanx.interop::ByteArray\n\nclass A {\n  Void f(ByteArray b) { ByteArray c := b; echo(c.size) }\n}\n",
        )
        assertEquals("fanx.interop.ByteArray", (targetsAt("ByteArray b").single() as PsiClass).qualifiedName)
        assertEmpty(myFixture.doHighlighting().filter { it.description?.startsWith("Cannot resolve") == true }.map { it.description })
    }

    fun testSlotsResolveToJavaMembers() {
        if (skip()) return
        myFixture.configureByText("A.fan", crypto)
        val getInstance = targetsAt("getInstance")
        assertTrue(getInstance.isNotEmpty())
        assertTrue(getInstance.all { it is PsiMethod && it.containingClass?.qualifiedName == "javax.crypto.Cipher" })
        assertTrue(targetsAt("doFinal").all { it is PsiMethod && it.name == "doFinal" })
        // `doFinal(toByteArray(...))`: of the overloads, the argument types pick `doFinal(byte[])` first.
        assertEquals("byte[]", (targetsAt("doFinal").first() as PsiMethod).parameterList.parameters.single().type.canonicalText)
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

    private fun localType(name: String): co.anbora.labs.fantom.lang.types.FanType? {
        val def = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
            myFixture.file, co.anbora.labs.fantom.lang.core.psi.FantomLocalDef::class.java,
        ).first { it.name == name }
        return co.anbora.labs.fantom.lang.types.FantomTypeInference.typeOfDeclaration(def)
    }

    /** Java return types map to Fantom types: primitives, `String` as `Str?`, other classes nullable. */
    fun testJavaTypesMapToFantom() {
        if (skip()) return
        myFixture.configureByText(
            "A.fan",
            """
            using [java] java.util::Base64
            using [java] java.lang::StringBuilder
            class A { Void f(ByteArray b) {
              enc := Base64.getEncoder()
              s := enc.encodeToString(b)
              sb := StringBuilder()
              n := sb.length()
              ok := sb.isEmpty()
            } }
            """.trimIndent(),
        )
        val enc = localType("enc") as co.anbora.labs.fantom.lang.types.FanType.Named
        assertEquals("Base64\$Encoder", enc.name)
        assertTrue(enc.nullable)
        assertEquals("java.util.Base64.Encoder", enc.javaClass)
        assertEquals(co.anbora.labs.fantom.lang.types.FanType.STR.withNullable(true), localType("s"))
        assertEquals("java.lang.StringBuilder", (localType("sb") as co.anbora.labs.fantom.lang.types.FanType.Named).javaClass)
        assertEquals(co.anbora.labs.fantom.lang.types.FanType.INT, localType("n"))
        assertEquals(co.anbora.labs.fantom.lang.types.FanType.BOOL, localType("ok"))
    }

    /** A receiver typed from a Java return value completes its members with no `using` line for it. */
    fun testCompletionOnInferredJavaReceiver() {
        if (skip()) return
        myFixture.configureByText(
            "A.fan",
            "using [java] java.util::Base64\nclass A { Void f() { enc := Base64.getEncoder(); enc.encodeToS<caret> } }",
        )
        val items = myFixture.completeBasic()?.map { it.lookupString }
        if (items == null) assertTrue(myFixture.editor.document.text, "enc.encodeToString" in myFixture.editor.document.text)
        else assertTrue(items.toString(), "encodeToString" in items)
    }

    private fun firstParamType(marker: String): String =
        (targetsAt(marker).first() as PsiMethod).parameterList.parameters.single().type.canonicalText

    /** Overloads: the compiler's rules pick by argument count and type; `Int` prefers `long`. */
    fun testOverloadsByArgumentTypes() {
        if (skip()) return
        myFixture.configureByText(
            "A.fan",
            """
            using [java] java.lang::StringBuilder
            class A { Void f() {
              sb := StringBuilder()
              sb.append(5)
              sb.append("x")
              sb.append(1.5f)
              sb.append(true)
            } }
            """.trimIndent(),
        )
        assertEquals("long", firstParamType("append(5"))
        assertEquals("java.lang.String", firstParamType("append(\"x"))
        assertEquals("double", firstParamType("append(1.5f"))
        assertEquals("boolean", firstParamType("append(true"))
        assertEmpty(myFixture.doHighlighting().filter { it.description?.startsWith("Cannot resolve") == true }.map { it.description })
    }

    /** `Outer$Inner` names a Java inner class. */
    fun testInnerClass() {
        if (skip()) return
        myFixture.configureByText(
            "A.fan",
            "using [java] java.util::Map\$Entry\nclass A { Void f(Map\$Entry e) { echo(e.getKey()) } }",
        )
        assertEquals("java.util.Map.Entry", (targetsAt("Map\$Entry e").single() as PsiClass).qualifiedName)
        assertTrue(targetsAt("getKey").all { it is PsiMethod && it.name == "getKey" })
        assertTrue(targetsAt("getKey").isNotEmpty())
    }

    /** A closure passed where Java wants a functional interface (`Runnable`, `Callable`). */
    fun testClosureAsFunctionalInterface() {
        if (skip()) return
        val resolver = FantomFfi.resolver()!!
        assertTrue(resolver.isFunctionalInterface("java.lang.Runnable", project))
        assertTrue(resolver.isFunctionalInterface("java.util.Comparator", project))
        assertFalse(resolver.isFunctionalInterface("java.util.List", project))
        assertFalse(resolver.isFunctionalInterface("java.lang.String", project))
        myFixture.configureByText(
            "A.fan",
            "using [java] java.util.concurrent::ExecutorService\nclass A { Void f(ExecutorService ex) { ex.submit |->| { echo(1) } } }",
        )
        val submit = targetsAt("submit")
        assertTrue(submit.isNotEmpty())
        assertTrue(submit.toString(), submit.all { (it as PsiMethod).parameterList.parametersCount == 1 })
    }

    /** `class FanDate : JDate`: inherited Java methods resolve, and an override gets the gutter arrow. */
    fun testSubclassOfJavaClass() {
        if (skip()) return
        myFixture.configureByText(
            "A.fan",
            """
            using [java] java.util::Date as JDate
            class FanDate : JDate {
              new make(Int millis) : super(millis) {}
              Int t() { return getTime() }
              Int u() { return this.getTime() }
              override Int hashCode() { return 1 }
            }
            """.trimIndent(),
        )
        assertEquals("java.util.Date", (targetsAt("getTime() }").single() as PsiMethod).containingClass?.qualifiedName)
        assertEquals("getTime", (targetsAt("getTime() }\n  over").first() as PsiMethod).name)
        val leaf = myFixture.file.findElementAt(myFixture.file.text.indexOf("hashCode"))!!
        val marker = co.anbora.labs.fantom.ide.generate.FantomOverrideLineMarkerProvider().getLineMarkerInfo(leaf)
        assertNotNull("override marker on hashCode", marker)
        assertEquals("Overrides JDate.hashCode", marker!!.lineMarkerTooltip)
        assertEmpty(myFixture.doHighlighting().filter { it.description?.startsWith("Cannot resolve") == true }.map { it.description })
    }

    /** A Java annotation used as a facet: the name resolves to the annotation, a value to its element. */
    fun testJavaAnnotationAsFacet() {
        if (skip()) return
        myFixture.configureByText(
            "A.fan",
            """
            using [java] java.lang.annotation::Retention
            @Retention { value = null }
            class A {}
            """.trimIndent(),
        )
        assertEquals("java.lang.annotation.Retention", (targetsAt("Retention {").single() as PsiClass).qualifiedName)
        val value = targetsAt("value =")
        assertTrue(value.toString(), value.isNotEmpty() && value.all { it is PsiMethod && it.name == "value" })
    }

    /**
     * docLang JavaFFI "Overloaded Methods" and "Dynamic Calls": `foo.a` reads the field,
     * `foo.a()` and `foo.a("x")` pick the method by arguments, `foo->a` hides the field.
     */
    fun testFieldMethodOverloadAndDynamicCall() {
        if (skip()) return
        myFixture.addFileToProject(
            "acme/Foo.java",
            "package acme; public class Foo { public String a; public String a() { return a; } " +
                "public void a(String x) { a = x; } public String b() { return a; } }",
        )
        myFixture.configureByText(
            "A.fan",
            """
            using [java] acme::Foo
            class A { Void f(Foo foo) {
              echo(foo.a)
              echo(foo.a())
              foo.a("x")
              echo(foo->a)
            } }
            """.trimIndent(),
        )
        assertTrue(targetsAt("a)\n").first() is PsiField)
        assertEquals(0, (targetsAt("a())").first() as PsiMethod).parameterList.parametersCount)
        assertEquals(1, (targetsAt("a(\"x").first() as PsiMethod).parameterList.parametersCount)
        val dyn = targetsAt("a)\n} }")
        assertTrue(dyn.toString(), dyn.isNotEmpty() && dyn.all { it is PsiMethod })
    }

    fun testFantomNamesStillReportedWithoutJavaPlugin() {
        if (skip()) return
        ExtensionTestUtil.maskExtensions(FantomFfi.EP_NAME, emptyList(), testRootDisposable)
        checkWarnings("using [java] java.util::Base64\nclass A { Void f() { x := <warning descr=\"Cannot resolve symbol 'zzqNope'\">zzqNope</warning>; echo(x) } }")
    }
}
