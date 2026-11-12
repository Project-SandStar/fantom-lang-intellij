package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomId
import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomParam
import co.anbora.labs.fantom.lang.core.psi.FantomStatementBlock
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.resolve.FantomReference
import co.anbora.labs.fantom.lang.resolve.FantomScope
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import co.anbora.labs.fantom.lang.library.FantomHome
import java.io.File

/**
 * Runs [FantomReference.resolveAll] on every identifier use in the Fantom `sys`
 * pod and prints what resolves, what is ambiguous and what is not found.
 * Read-only measurement: the tally is diagnostics, the spot checks assert.
 *
 * Note: `sys` has no method bodies (every slot is native), so the corpus
 * exercises TYPE and SLOT references in signatures and param defaults, and
 * BARE references to params. Locals and bare self calls are covered by a
 * small probe file added after the tally, so the numbers stay pure `sys`.
 *
 * Skips when the Fantom source tree is not on this machine.
 */
class FantomResolveCorpusTest : BasePlatformTestCase() {

    private val sysDir = File(System.getProperty("fantom.sys.dir") ?: (FantomHome.detect()?.path + "/src/sys/fan"))
    private val tag = "FantomResolveCorpusTest:"

    private class Tally {
        var total = 0
        var resolved = 0
        var ambiguous = 0
        var unresolved = 0
        val unresolvedNames = HashMap<String, Int>()
        val ambiguousNames = HashMap<String, Int>()      // name -> occurrences
        val ambiguousTargets = HashMap<String, Int>()    // name -> max targets seen
        val wrongKind = ArrayList<String>()
        val targetClasses = sortedMapOf<String, Int>()   // target PSI class -> count
        override fun toString() = "total=$total resolved=$resolved ambiguous=$ambiguous unresolved=$unresolved"
    }

    private val files = LinkedHashMap<String, PsiFile>()

    fun testSysPodResolve() {
        if (!sysDir.isDirectory) {
            println("$tag $sysDir missing, skipped")
            return
        }
        val sources = sysDir.listFiles { f -> f.isFile && f.extension == "fan" }?.sortedBy { it.name }.orEmpty()
        assertFalse("no .fan files in $sysDir", sources.isEmpty())

        val loadStart = System.nanoTime()
        for (f in sources) {
            files[f.name] = myFixture.addFileToProject("sys/fan/${f.name}", StringUtil.convertLineSeparators(f.readText()))
        }
        println("$tag files=${files.size} loadMs=${(System.nanoTime() - loadStart) / 1_000_000}")

        tally()
        guardDeclarationNamesHaveNoReference()
        spotChecksOnSys()
        spotChecksOnProbe()
        guardDeclarationNamesHaveNoReference() // again, now with the probe's local and method names
    }

    // ---- 2 + 3: tally ---------------------------------------------------

    private fun tally() {
        val perKind = FantomReference.Kind.entries.associateWith { Tally() }
        val failures = ArrayList<String>()
        val noRefByParent = sortedMapOf<String, Int>()
        var blocks = 0
        var localDefs = 0

        val start = System.nanoTime()
        for ((name, psi) in files) {
            blocks += PsiTreeUtil.findChildrenOfType(psi, FantomStatementBlock::class.java).size
            localDefs += PsiTreeUtil.findChildrenOfType(psi, FantomLocalDef::class.java).size
            for (id in PsiTreeUtil.findChildrenOfType(psi, FantomId::class.java)) {
                val ref = id.reference as? FantomReference
                if (ref == null) {
                    if (id.parent !is FantomNamedElement) {
                        noRefByParent.merge(id.parent.javaClass.simpleName.removeSuffix("Impl"), 1, Int::plus)
                    }
                    continue
                }
                val t = perKind.getValue(ref.kind)
                t.total++
                val targets = try {
                    ref.resolveAll()
                } catch (e: Throwable) {
                    failures += "$name@${id.textOffset} ${ref.kind} '${ref.name}': $e"
                    continue
                }
                if (targets.isEmpty()) {
                    t.unresolved++
                    t.unresolvedNames.merge(ref.name, 1, Int::plus)
                } else {
                    t.resolved++
                    if (targets.size > 1) {
                        t.ambiguous++
                        t.ambiguousNames.merge(ref.name, 1, Int::plus)
                        t.ambiguousTargets.merge(ref.name, targets.size, ::maxOf)
                    }
                    for (target in targets) {
                        t.targetClasses.merge(target.javaClass.simpleName.removeSuffix("Impl"), 1, Int::plus)
                        val bad = when (ref.kind) {
                            FantomReference.Kind.TYPE -> !FantomScope.isTypeDef(target)
                            FantomReference.Kind.SLOT -> !FantomScope.isSlot(target)
                            FantomReference.Kind.BARE -> false
                        }
                        if (bad) t.wrongKind += "$name '${ref.name}' -> ${target.javaClass.simpleName.removeSuffix("Impl")} in ${target.containingFile.name}"
                    }
                }
            }
        }
        val resolveMs = (System.nanoTime() - start) / 1_000_000

        println("$tag statementBlocks=$blocks localDefs=$localDefs (sys has no method bodies)")
        println("$tag ids without reference, by parent (declaration names excluded): $noRefByParent")
        val all = Tally()
        for ((kind, t) in perKind) {
            println("$tag $kind $t")
            all.total += t.total; all.resolved += t.resolved; all.ambiguous += t.ambiguous; all.unresolved += t.unresolved
        }
        println("$tag ALL $all resolveMs=$resolveMs")

        for ((kind, t) in perKind) {
            println("$tag top unresolved $kind: ${top(t.unresolvedNames, 15)}")
        }
        val slot = perKind.getValue(FantomReference.Kind.SLOT)
        println("$tag top ambiguous SLOT (name=occurrences/maxTargets): " +
            top(slot.ambiguousNames, 10).joinToString(", ") { (n, c) -> "$n=$c/${slot.ambiguousTargets[n]}" })

        for ((kind, t) in perKind) println("$tag $kind target classes: ${t.targetClasses}")

        // Unresolved names that are index keys. A key with no entries is stale; a key whose
        // entries are of the other kind is a name clash; a key with entries of the same kind
        // is a resolver bug.
        val scope = GlobalSearchScope.projectScope(project)
        for ((kind, t) in perKind) {
            val wantType = kind == FantomReference.Kind.TYPE
            for (n in t.unresolvedNames.keys.sorted()) {
                val entries = FantomDeclarationIndex.entries(n, project, scope)
                if (entries.isEmpty()) continue
                val where = FantomDeclarationIndex.findDeclarations(n, project, scope) { true }
                    .map { "${it.javaClass.simpleName.removeSuffix("Impl")}@${it.containingFile.name}:${lineOf(it)}" }
                val verdict = if (entries.any { it.kind.isType == wantType }) "<- BUG CANDIDATE" else "(other kind, name clash)"
                println("$tag unresolved $kind '$n' is an index key: entries=$entries at=$where $verdict")
            }
        }
        for ((kind, t) in perKind) {
            if (t.wrongKind.isNotEmpty()) {
                println("$tag $kind refs with a target of the wrong kind: ${t.wrongKind.size}, e.g. ${t.wrongKind.take(5)}")
            }
        }

        assertTrue("no references found in ${files.size} sys files", all.total > 0)
        assertTrue("resolve took ${resolveMs}ms, limit 120s", resolveMs < 120_000)
        if (failures.isNotEmpty()) fail("${failures.size} resolveAll calls threw:\n  ${failures.take(20).joinToString("\n  ")}")
    }

    private fun lineOf(e: PsiElement): Int =
        (e.containingFile.viewProvider.document?.getLineNumber(e.textOffset) ?: -1) + 1

    private fun top(counts: Map<String, Int>, n: Int): List<Pair<String, Int>> =
        counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(n).map { it.key to it.value }

    // ---- 5: guard -------------------------------------------------------

    private fun guardDeclarationNamesHaveNoReference() {
        val bad = ArrayList<String>()
        var checked = 0
        for ((name, psi) in files) {
            for (id in PsiTreeUtil.findChildrenOfType(psi, FantomId::class.java)) {
                if (id.parent !is FantomNamedElement) continue
                checked++
                if (id.reference != null) bad += "$name@${id.textOffset} '${id.text}'"
            }
        }
        println("$tag declaration-name ids checked=$checked withReference=${bad.size}")
        assertTrue("no declaration names found", checked > 0)
        assertTrue("declaration names with a reference: ${bad.take(10)}", bad.isEmpty())
    }

    // ---- 4: spot checks -------------------------------------------------

    private fun spotChecksOnSys() {
        // TYPE: `Int` in a Str method signature -> class Int in Int.fan.
        val int = resolveOne("Str.fan", "Int? index(Str s, Int offset := 0)", "Int", occurrence = 1)
        assertEquals("Int.fan", int.containingFile.name)
        assertTrue("Int target is ${int.javaClass.simpleName}", int is FantomClassDef)

        // BARE: `off` in a param default -> the `off` param of the same method.
        val off = resolveOne("Buf.fan", "This writeChars(Str str, Int off := 0, Int len := str.size-off)", "off")
        assertTrue("off target is ${off.javaClass.simpleName}", off is FantomParam)
        assertEquals("Buf.fan", off.containingFile.name)

        // SLOT with owner: `cur` in `TimeZone.cur` -> only TimeZone.cur(), not Env.cur or Locale.cur.
        val cur = resolveOne("Date.fan", "static Date today(TimeZone tz := TimeZone.cur)", "cur")
        assertTrue("cur target is ${cur.javaClass.simpleName}", cur is FantomMethodDef)
        assertEquals("TimeZone.fan", cur.containingFile.name)
    }

    /** Locals and bare self calls do not occur in `sys`; a probe file checks them against the full index. */
    // `size` and `hash` collide with many `sys` slots, so these checks also test precedence.
    private fun spotChecksOnProbe() {
        val probe = myFixture.addFileToProject(
            "probe/Probe.fan",
            """
            class Probe
            {
              Int size() { return 1 }
              Int twice() { return size() * 2 }
              Int local() { hash := 1; return hash + 1 }
            }
            """.trimIndent(),
        )
        files["Probe.fan"] = probe

        val size = resolveOne("Probe.fan", "Int twice() { return size() * 2 }", "size")
        assertTrue("size target is ${size.javaClass.simpleName}", size is FantomMethodDef)
        assertEquals("Probe.fan", size.containingFile.name)

        val hash = resolveOne("Probe.fan", "Int local() { hash := 1; return hash + 1 }", "hash")
        assertTrue("hash target is ${hash.javaClass.simpleName}", hash is FantomLocalDef)
        assertEquals("Probe.fan", hash.containingFile.name)
    }

    /**
     * Finds the [occurrence]-th id (0-based) named [idText] that has a reference, on the one
     * line of [fileName] whose trimmed text equals [lineText]; resolves it and asserts one target.
     */
    private fun resolveOne(fileName: String, lineText: String, idText: String, occurrence: Int = 0): FantomNamedElement {
        val psi = files[fileName] ?: error("$fileName not loaded")
        val doc = psi.viewProvider.document ?: error("no document for $fileName")
        val lines = (0 until doc.lineCount).filter {
            doc.text.substring(doc.getLineStartOffset(it), doc.getLineEndOffset(it)).trim() == lineText
        }
        assertEquals("lines in $fileName equal to '$lineText'", 1, lines.size)
        val start = doc.getLineStartOffset(lines[0])
        val end = doc.getLineEndOffset(lines[0])
        val ids = PsiTreeUtil.findChildrenOfType(psi, FantomId::class.java)
            .filter { it.textOffset in start until end && it.text == idText && it.reference != null }
            .sortedBy { it.textOffset }
        assertTrue("$fileName: ids '$idText' with a reference on line: ${ids.size}, wanted index $occurrence", ids.size > occurrence)
        val ref = ids[occurrence].reference as FantomReference
        val targets = ref.resolveAll()
        println("$tag spot $fileName '$idText' ${ref.kind} -> ${targets.map { "${it.javaClass.simpleName.removeSuffix("Impl")}@${it.containingFile.name}" }}")
        assertEquals("$fileName '$idText' (${ref.kind}) targets: $targets", 1, targets.size)
        return targets[0]
    }
}
