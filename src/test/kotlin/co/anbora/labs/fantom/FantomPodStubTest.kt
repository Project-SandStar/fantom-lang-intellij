package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.FantomParserDefinition
import co.anbora.labs.fantom.lang.pod.ApiDocParser
import co.anbora.labs.fantom.lang.pod.FantomPodStubs
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import java.io.File
import java.nio.file.Files

/**
 * Stubs generated from compiled pods: the writer's output for the apidoc samples, the
 * `haystack.pod` stubs of SkySpark 3.0.29, and every pod of SkySpark 3.0.29 parsing with
 * no PSI errors. The SkySpark tests are skipped when the install is missing.
 */
class FantomPodStubTest : ParsingTestCase("", "fan", FantomParserDefinition()) {

    private val skyspark = File(System.getProperty("user.home"), "skyspark/skyspark-3.0.29")
    private val lib = File(skyspark, "lib/fan")
    private val samples = File("src/test/testData/apidoc")

    override fun getTestDataPath(): String = "src/test/testData"

    private fun tempBase(): File = Files.createTempDirectory("fantom-pod-stubs").toFile()

    /** PSI errors and bad characters in [text], with the first message. */
    private fun errors(name: String, text: String): Pair<Int, String> {
        val psi = createPsiFile(name, text)
        ensureParsed(psi)
        val errs = PsiTreeUtil.collectElementsOfType(psi, PsiErrorElement::class.java).toList()
        var bad = 0
        PsiTreeUtil.processElements(psi) {
            if (it is LeafPsiElement && it.elementType == TokenType.BAD_CHARACTER) bad++
            true
        }
        val first = errs.firstOrNull()?.let { e ->
            val line = text.substring(0, e.textOffset).count { it == '\n' } + 1
            "line $line: ${e.errorDescription}: " + text.lines().getOrElse(line - 1) { "" }.trim()
        } ?: ""
        return (errs.size + bad) to first
    }

    fun testSampleStubsParse() {
        val files = samples.walkTopDown().filter { it.extension == "apidoc" }.toList()
        val byPod = files.groupBy { it.parentFile.parentFile.name }
        val failures = ArrayList<String>()
        for ((pod, list) in byPod) {
            val docs = list.map { ApiDocParser.parse(it.readText()) }
            val own = docs.mapTo(HashSet()) { it.name }
            for (t in docs) {
                val text = FantomPodStubs.StubWriter(pod, "1.0", own).write(t)
                val (n, first) = errors("${t.name}.fan", text)
                if (n > 0) failures += "$pod/${t.name}.fan: $n errors, $first"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    fun testEnumStubText() {
        val t = ApiDocParser.parse(File(samples, "axon/doc/SignalType.apidoc").readText())
        val text = FantomPodStubs.StubWriter("axon", "3.0.29", setOf("SignalType")).write(t)
        assertTrue(text, text.startsWith("** Generated from axon.pod 3.0.29; read-only.\n"))
        assertTrue(text, text.contains("@Serializable { simple = true }"))
        assertTrue(text, text.contains("public enum class SignalType"))
        assertTrue(text, text.contains("  start,\n"))
    }

    fun testHaystackStubs() {
        val pod = File(lib, "haystack.pod")
        if (!pod.isFile) { println("FantomPodStubTest skipped: ${pod.path} missing"); return }
        val base = tempBase()
        try {
            assertTrue(FantomPodStubs.needsGeneration(pod, base))
            val n = FantomPodStubs.generate(pod, base)
            assertTrue("$n", n > 50)
            assertFalse(FantomPodStubs.needsGeneration(pod, base))
            val src = FantomPodStubs.podSource(pod, base)!!
            assertEquals("haystack", src.pod)
            val fan = src.roots.single()
            assertEquals("fan", fan.name)
            assertEquals("haystack", fan.parentFile.name)
            val etc = File(fan, "Etc.fan")
            assertFalse(etc.canWrite())
            val text = etc.readText()
            assertTrue(text, text.startsWith("** Generated from haystack.pod 3.0.29; read-only."))
            assertTrue(text, text.contains("const class Etc"))
            assertTrue(text, text.contains("static native Dict emptyDict()"))
            assertEquals(0 to "", errors("Etc.fan", text))
            // Regenerating replaces the read-only files.
            File(src.podDir.parentFile, FantomPodStubs.MARKER).writeText("stale")
            assertTrue(FantomPodStubs.needsGeneration(pod, base))
            assertEquals(n, FantomPodStubs.generate(pod, base))
        } finally {
            base.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }

    fun testAllSkySparkPodsParse() {
        if (!lib.isDirectory) { println("FantomPodStubTest skipped: ${lib.path} missing"); return }
        val base = tempBase()
        try {
            val pods = FantomPodStubs.podFiles(skyspark)
            val t0 = System.nanoTime()
            var files = 0
            for (pod in pods) files += FantomPodStubs.generate(pod, base)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("FantomPodStubTest: ${pods.size} pods, $files stubs generated in ${ms}ms")
            val apidocs = pods.map { pod ->
                java.util.zip.ZipFile(pod).use { z -> z.entries().asSequence().count { it.name.startsWith("doc/") && it.name.endsWith(".apidoc") } }
            }
            println("FantomPodStubTest: ${apidocs.sum()} apidoc entries, ${apidocs.count { it == 0 }} pods without apidoc")
            assertEquals(apidocs.sum(), files)
            val results = base.walkTopDown().filter { it.extension == "fan" }.map { f ->
                val (n, first) = errors(f.name, f.readText())
                Triple(f, n, first)
            }.toList()
            val total = results.sumOf { it.second }
            println("FantomPodStubTest: ${results.size} stubs parsed, $total PSI errors")
            val worst = results.filter { it.second > 0 }.sortedByDescending { it.second }.take(5)
            for ((f, n, first) in worst) println("  ${f.relativeTo(base)}: $n errors, $first")
            assertEquals(pods.size, base.listFiles { f -> f.isDirectory }!!.size)
            assertEquals(worst.joinToString("\n") { "${it.first.name}: ${it.second} ${it.third}" }, 0, total)
        } finally {
            base.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }
}
