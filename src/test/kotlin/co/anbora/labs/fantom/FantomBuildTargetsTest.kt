package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.compiler.FantomBuildRequest
import co.anbora.labs.fantom.ide.compiler.FantomBuildTargets
import co.anbora.labs.fantom.ide.compiler.FantomCompilerService
import co.anbora.labs.fantom.ide.compiler.HaxallPods
import co.anbora.labs.fantom.lang.library.FantomInstall
import co.anbora.labs.fantom.lang.library.FantomInstalls
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Build Pod per target, on the installs of this machine (skips when they are missing):
 * a temp pod built for SkySpark 4.0.7 and for Fantom 1.0.84, a Haxall target that borrows
 * SkySpark's pods, and the "write into lib/fan" box. Nothing is written into a real install.
 */
class FantomBuildTargetsTest : BasePlatformTestCase() {
    private val user = File(System.getProperty("user.home"))
    private val sky = File(user, "skyspark/skyspark-4.0.7")
    private val fantom = File(user, "fantom/fantom-1.0.84")
    private val hx = File(user, "haxall/haxall-4.0.7")
    private val temps = ArrayList<File>()

    override fun setUp() {
        super.setUp()
        FantomSettings.getInstance(project).compileOnSave = false
    }

    override fun tearDown() {
        try {
            val s = FantomSettings.getInstance(project)
            s.installs = emptyList()
            s.defaultTarget = null
            s.lastBuildTarget = null
            s.compileOnSave = true
            temps.forEach(FileUtil::delete)
        } finally {
            super.tearDown()
        }
    }

    private fun ready(): Boolean {
        val ok = sky.isDirectory && fantom.isDirectory && File(fantom, "lib/fan/sys.pod").isFile
        if (!ok) println("FantomBuildTargetsTest skipped: needs ~/skyspark/skyspark-4.0.7 and ~/fantom/fantom-1.0.84")
        return ok
    }

    private fun tempDir(prefix: String) = FileUtil.createTempDirectory(prefix, null, true).also { temps += it }

    private fun pod(name: String, depends: String, body: String): File {
        val dir = tempDir("fantom-target")
        File(dir, "build.fan").writeText(
            "using build\nclass Build : BuildPod\n{\n  new make()\n  {\n    podName = \"$name\"\n" +
                "    summary = \"test\"\n    version = Version(\"0.1\")\n    depends = [$depends]\n    srcDirs = [`fan/`]\n  }\n}\n"
        )
        File(dir, "fan").mkdirs()
        File(dir, "fan/Foo.fan").writeText(body)
        return dir
    }

    /** Ticks the folders in [paths] among a scan of [roots] plus [custom]. */
    private fun tick(roots: List<File>, custom: List<File>, vararg paths: String, chosen: Map<String, String> = emptyMap()): List<FantomInstall> {
        val choices = paths.associateWith { FantomInstalls.Choice(true, chosen[it], false) }
        val rows = FantomInstalls.pair(FantomInstalls.scan(roots, custom), choices)
        FantomSettings.getInstance(project).installs = rows
        return rows.filter { it.enabled }
    }

    private fun listing(dir: File): List<String> = (File(dir, "lib/fan").list() ?: emptyArray()).sorted()

    fun testBuildForTwoTargetsWritesNothingIntoInstalls() {
        if (!ready()) return
        val targets = tick(listOf(File(user, "fantom"), File(user, "skyspark")), emptyList(), sky.path, fantom.path)
        assertEquals(2, targets.size)
        val skyBefore = listing(sky)
        val fantomBefore = listing(fantom)
        val dir = pod("ijtargetpod", "\"sys 1.0\"", "class Foo { Str hi() { \"hi\" } }\n")
        val service = FantomCompilerService.getInstance(project)
        for (t in targets) {
            val out = FantomBuildTargets.build(project, dir, "ijtargetpod", FantomBuildRequest(t), null)
            assertNull(t.name + ": " + out.error, out.error)
            val r = out.result!!
            assertTrue(t.name, r.completed)
            assertEquals(t.name + " " + r.byFile, 0, r.exitCode)
            assertNull(out.written)
            assertTrue(t.name, File(service.workDir(dir), "lib/fan/ijtargetpod.pod").isFile)
            assertEquals(t.path, FantomSettings.getInstance(project).lastBuildTarget)
        }
        assertEquals(skyBefore, listing(sky))
        assertEquals(fantomBefore, listing(fantom))
        // the SkySpark build ran with SkySpark's own bin/fan
        assertEquals(sky, targets.first { it.dir == sky }.runner)
    }

    /** A pod using haystack, for Haxall 4.0.7: SkySpark 4.0.7's pods are borrowed, and the next build does not keep them. */
    fun testHaxallTargetBorrowsSkySparkPods() {
        if (!ready() || !hx.isDirectory) return
        val targets = tick(listOf(File(user, "fantom"), File(user, "haxall"), File(user, "skyspark")), emptyList(), hx.path)
        val target = targets.single()
        assertEquals(sky.path, target.skysparkPath)
        assertEquals(HaxallPods.BORROW, FantomBuildTargets.autoHaxallPods(project, target))
        val dir = pod("ijhxpod", "\"sys 1.0\", \"haystack 3.1+\"", "using haystack\nclass Foo { Obj d() { Etc.dict0 } }\n")
        val ok = FantomBuildTargets.build(project, dir, "ijhxpod", FantomBuildRequest(target, HaxallPods.BORROW), null)
        assertEquals(ok.result?.byFile.toString(), 0, ok.result?.exitCode)
        val props = File(FantomCompilerService.getInstance(project).workDir(dir), "fan.props")
        assertTrue(props.readText().contains("path=" + sky.absolutePath))
        val none = FantomBuildTargets.build(project, dir, "ijhxpod", FantomBuildRequest(target, HaxallPods.NONE), null)
        assertFalse(props.readText().contains("path="))
        assertTrue(none.result!!.exitCode != 0) // haystack is not found without the borrowed pods
    }

    /** The box writes the pod into the target's lib/fan; here the target is a fake Haxall in a temp folder. */
    fun testWriteIntoTargetOnlyWhenTicked() {
        if (!ready()) return
        val root = tempDir("fake-haxall")
        val fake = File(root, "haxall-9.9.9").apply { File(this, "src/core/haystack/fan").mkdirs() }
        val target = tick(listOf(File(user, "fantom")), listOf(fake), fake.path, chosen = mapOf(fake.path to fantom.path)).single()
        assertEquals(fantom, target.runner)
        val dir = pod("ijwritepod", "\"sys 1.0\"", "class Foo {}\n")
        val off = FantomBuildTargets.build(project, dir, "ijwritepod", FantomBuildRequest(target, HaxallPods.NONE, false), null)
        assertEquals(0, off.result?.exitCode)
        assertFalse(File(fake, "lib/fan/ijwritepod.pod").exists())
        val on = FantomBuildTargets.build(project, dir, "ijwritepod", FantomBuildRequest(target, HaxallPods.NONE, true), null)
        assertNull(on.error)
        assertEquals(File(fake, "lib/fan/ijwritepod.pod"), on.written)
        assertTrue(File(fake, "lib/fan/ijwritepod.pod").isFile)
    }

    /** SkySpark 3.0.29 has no Fantom 1.0.76: building for it is refused with the warning. */
    fun testMissingFantomRefusesBuild() {
        val old = File(user, "skyspark/skyspark-3.0.29")
        if (!old.isDirectory) return
        val target = tick(listOf(File(user, "fantom"), File(user, "skyspark")), emptyList(), old.path).single()
        assertNull(target.runner)
        val out = FantomBuildTargets.build(project, pod("ijold", "\"sys 1.0\"", "class Foo {}\n"), "ijold", FantomBuildRequest(target), null)
        assertNull(out.result)
        assertTrue(out.error!!, out.error!!.contains("Fantom 1.0.76 missing"))
    }
}
