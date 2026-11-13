package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.inspections.FantomUnresolvedReferenceInspection
import co.anbora.labs.fantom.ide.java.FantomPodJarRootsProvider
import co.anbora.labs.fantom.lang.resolve.FantomReference
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

/**
 * A pod that bundles its Java library (`<pod>/jar/serial.jar`, as bassgModbusRtu bundles
 * `jssc.jar`): `using [java] jssc::SerialPort as SerialPortImpl` resolves to the class in
 * that jar, and its methods resolve on a receiver. The jar is compiled here from a tiny
 * Java source. Skips when no system Java compiler is present.
 */
class FantomPodJarFfiTest : FantomInspectionTestBase() {

    private fun buildJar(dest: File): Boolean {
        val javac = ToolProvider.getSystemJavaCompiler() ?: return false
        val src = Files.createTempDirectory("ffi-src").toFile()
        val pkg = File(src, "jssc").apply { mkdirs() }
        File(pkg, "SerialPort.java").writeText(
            "package jssc; public class SerialPort { public SerialPort(String n) {} " +
                "public boolean openPort() { return true; } public static final int BAUDRATE_9600 = 9600; }",
        )
        File(pkg, "SerialPortList.java").writeText(
            "package jssc; public class SerialPortList { public static String[] getPortNames() { return new String[0]; } }",
        )
        val out = File(src, "classes").apply { mkdirs() }
        if (javac.run(null, null, null, "-d", out.path, File(pkg, "SerialPort.java").path, File(pkg, "SerialPortList.java").path) != 0) return false
        dest.parentFile.mkdirs()
        JarOutputStream(dest.outputStream()).use { jar ->
            out.walkTopDown().filter { it.isFile }.forEach { f ->
                jar.putNextEntry(JarEntry(f.relativeTo(out).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(jar) }
                jar.closeEntry()
            }
        }
        return true
    }

    fun testPodJarClassesAndMembersResolve() = check("jar/serial.jar")

    /** bassgMSAccessLib bundles its jars in `jars/` (plural). */
    fun testJarsFolder() = check("jars/serial.jar")

    fun testLibJavaFolder() = check("lib/java/serial.jar")

    /** A jar anywhere in the pod counts when build.fan names it. */
    fun testJarNamedInBuildFan() = check("vendor/serial.jar", "  jars = [`vendor/serial.jar`]\n")

    private fun check(jarPath: String, buildExtra: String = "") {
        val root = Files.createTempDirectory("ffi-pod").toFile()
        val pod = File(root, "serialpod")
        if (!buildJar(File(pod, jarPath))) {
            println("FantomPodJarFfiTest skipped: no system Java compiler")
            return
        }
        File(pod, "build.fan").writeText("using build\nclass Build : BuildPod { new make() { podName = \"serialpod\"\n$buildExtra } }\n")
        val text = """
            using [java] jssc::SerialPort as SerialPortImpl
            using [java] jssc::SerialPortList

            class Serial {
              Void open() {
                SerialPortImpl p := SerialPortImpl("COM1")
                p.openPort()
                echo(SerialPortImpl.BAUDRATE_9600)
                echo(SerialPortList.getPortNames())
              }
            }
        """.trimIndent()
        File(pod, "fan/Serial.fan").apply { parentFile.mkdirs() }.writeText(text)
        val podDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(pod)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, podDir)
        PsiTestUtil.addContentRoot(module, podDir)
        try {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            assertTrue(FantomPodJarRootsProvider.podJarRoots(project).any { it.path.endsWith("serial.jar!/") })

            myFixture.enableInspections(FantomUnresolvedReferenceInspection())
            myFixture.configureFromExistingVirtualFile(podDir.findFileByRelativePath("fan/Serial.fan")!!)
            fun targets(marker: String, delta: Int = 0) =
                (myFixture.file.findReferenceAt(myFixture.file.text.indexOf(marker) + delta) as FantomReference)
                    .multiResolve(false).mapNotNull { it.element }

            assertEquals("jssc.SerialPort", (targets("SerialPortImpl p").single() as PsiClass).qualifiedName)
            assertEquals("jssc.SerialPortList", (targets("SerialPortList.get").single() as PsiClass).qualifiedName)
            assertEquals("openPort", (targets("openPort").single() as PsiMethod).name)
            assertEquals("getPortNames", (targets("getPortNames").single() as PsiMethod).name)
            assertTrue(targets("BAUDRATE_9600").isNotEmpty())
            val unresolved = myFixture.doHighlighting().filter { it.description?.startsWith("Cannot resolve") == true }
            assertEmpty(unresolved.map { it.description })
        } finally {
            PsiTestUtil.removeContentEntry(module, podDir)
        }
    }
}
