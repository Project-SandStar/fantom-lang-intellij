package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomId
import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomStatementBlock
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomSettings
import co.anbora.labs.fantom.lang.resolve.FantomReference
import co.anbora.labs.fantom.lang.resolve.FantomScope
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import java.io.File

/**
 * Measures [FantomReference.resolveAll] where it matters: on code with method bodies
 * (the `util` pod, loaded as project files) with the whole Fantom install, `sys`
 * included, available as a library.
 *
 * Read-only measurement plus a few hard asserts. Skips when no Fantom home is on
 * this machine. `-Dfantom.pod.dir=<dir>` picks another pod folder than `util/fan`.
 *
 * Note: the library also contains `util`, so every util declaration exists twice in
 * `allScope` (project copy and library copy). The tally counts such "twins" apart so
 * that the ambiguity numbers stay readable.
 */
class FantomResolveBodiesCorpusTest : BasePlatformTestCase() {

    private val tag = "FantomResolveBodiesCorpusTest:"
    private var home: File? = null
    private val files = LinkedHashMap<String, PsiFile>()

    override fun setUp() {
        super.setUp()
        home = FantomHome.detect()
        val h = home ?: return
        FantomSettings.getInstance(project).homePath = h.path
        WriteAction.runAndWait<RuntimeException> {
            ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), false, true)
        }
        FileBasedIndex.getInstance().ensureUpToDate(FantomDeclarationIndex.NAME, project, null)
    }

    override fun tearDown() {
        try {
            FantomSettings.getInstance(project).homePath = null
        } finally {
            super.tearDown()
        }
    }

    private class Tally {
        var total = 0
        var resolved = 0
        var ambiguous = 0
        var ambiguousTwinOnly = 0     // >1 targets, but one target per project/library twin pair
        var unresolved = 0
        var targetsProject = 0
        var targetsLibSys = 0
        var targetsLibUtilTwin = 0    // library copy of a declaration that also lives in the project
        var targetsLibOther = 0
        val unresolvedNames = HashMap<String, Int>()
        val unresolvedExamples = ArrayList<String>()
        val ambiguousNames = HashMap<String, Int>()      // name -> occurrences
        val ambiguousTargets = HashMap<String, Int>()    // name -> max targets seen
        val ambiguousNamesNoTwin = HashMap<String, Int>()
        val ambiguousTargetsNoTwin = HashMap<String, Int>()
        val wrongKind = ArrayList<String>()
        val targetClasses = sortedMapOf<String, Int>()
        val suspectLocals = ArrayList<String>()         // BARE -> local-like target in another method or after the use
        fun pct(n: Int) = if (total == 0) 0.0 else n * 100.0 / total
        override fun toString() =
            "total=$total resolved=$resolved (%.1f%%) ambiguous=$ambiguous (twinOnly=$ambiguousTwinOnly) unresolved=$unresolved (%.1f%%) " +
                "targets: project=$targetsProject libSys=$targetsLibSys libUtilTwin=$targetsLibUtilTwin libOther=$targetsLibOther"
        fun line() = String.format(toString(), pct(resolved), pct(unresolved))
    }

    private fun skip(): Boolean {
        if (home == null) println("$tag skipped: no Fantom home found")
        return home == null
    }

    fun testUtilPodResolveWithSysLibrary() {
        if (skip()) return
        val podDir = System.getProperty("fantom.pod.dir")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(home, "src/util/fan")
        assertTrue("$podDir is not a folder", podDir.isDirectory)
        val sources = podDir.listFiles { f -> f.isFile && f.extension == "fan" }?.sortedBy { it.name }.orEmpty()
        assertFalse("no .fan files in $podDir", sources.isEmpty())
        val podName = podDir.parentFile.name

        val loadStart = System.nanoTime()
        for (f in sources) {
            files[f.name] = myFixture.addFileToProject("$podName/fan/${f.name}", StringUtil.convertLineSeparators(f.readText()))
        }
        println("$tag pod=$podName files=${files.size} loadMs=${(System.nanoTime() - loadStart) / 1_000_000} home=$home")

        val perKind = tally(podName)
        guardDeclarationNamesHaveNoReference()
        spotChecks()
        thresholds(perKind)
    }

    // ---- 3 + 4: tally ---------------------------------------------------

    private fun tally(podName: String): Map<FantomReference.Kind, Tally> {
        val perKind = FantomReference.Kind.entries.associateWith { Tally() }
        val failures = ArrayList<String>()
        val noRefByParent = sortedMapOf<String, Int>()
        val fileIndex = ProjectFileIndex.getInstance(project)
        var blocks = 0
        var localDefs = 0

        val start = System.nanoTime()
        for ((fileName, psi) in files) {
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
                    failures += "$fileName:${lineOf(id)} ${ref.kind} '${ref.name}': $e"
                    continue
                }
                if (targets.isEmpty()) {
                    t.unresolved++
                    t.unresolvedNames.merge(ref.name, 1, Int::plus)
                    if (t.unresolvedExamples.size < 5) t.unresolvedExamples += "$fileName:${lineOf(id)} '${ref.name}'"
                    continue
                }
                t.resolved++
                // Where do the targets live?
                val projectTargets = targets.filter { !fileIndex.isInLibrarySource(it.containingFile.virtualFile) }
                var distinct = projectTargets.size
                for (target in targets) {
                    val vf = target.containingFile.virtualFile
                    t.targetClasses.merge(target.javaClass.simpleName.removeSuffix("Impl"), 1, Int::plus)
                    if (!fileIndex.isInLibrarySource(vf)) {
                        t.targetsProject++
                    } else {
                        val pod = vf.parent?.parent?.name
                        val twin = pod == podName && projectTargets.any { isTwin(it, target) }
                        when {
                            twin -> t.targetsLibUtilTwin++
                            pod == "sys" -> t.targetsLibSys++
                            else -> t.targetsLibOther++
                        }
                        if (!twin) distinct++
                    }
                    val bad = when (ref.kind) {
                        FantomReference.Kind.TYPE -> !FantomScope.isTypeDef(target)
                        FantomReference.Kind.SLOT -> !FantomScope.isSlot(target)
                        FantomReference.Kind.BARE -> false
                    }
                    if (bad) t.wrongKind += "$fileName:${lineOf(id)} '${ref.name}' -> ${target.javaClass.simpleName.removeSuffix("Impl")} in ${target.containingFile.name}"
                    if (ref.kind == FantomReference.Kind.BARE && FantomScope.isLocalLike(target)) {
                        val sameMethod = PsiTreeUtil.getParentOfType(id, FantomMethodDef::class.java) ===
                            PsiTreeUtil.getParentOfType(target, FantomMethodDef::class.java)
                        if (!sameMethod || target.textOffset > id.textOffset) {
                            t.suspectLocals += "$fileName:${lineOf(id)} '${ref.name}' -> ${target.javaClass.simpleName.removeSuffix("Impl")}@${lineOf(target)}"
                        }
                    }
                }
                if (targets.size > 1) {
                    t.ambiguous++
                    t.ambiguousNames.merge(ref.name, 1, Int::plus)
                    t.ambiguousTargets.merge(ref.name, targets.size, ::maxOf)
                    if (distinct > 1) {
                        t.ambiguousNamesNoTwin.merge(ref.name, 1, Int::plus)
                        t.ambiguousTargetsNoTwin.merge(ref.name, distinct, ::maxOf)
                    } else {
                        t.ambiguousTwinOnly++
                    }
                }
            }
        }
        val resolveMs = (System.nanoTime() - start) / 1_000_000

        println("$tag statementBlocks=$blocks localDefs=$localDefs")
        println("$tag ids without reference, by parent (declaration names excluded): $noRefByParent")
        val all = Tally()
        for ((kind, t) in perKind) {
            println("$tag $kind ${t.line()}")
            all.total += t.total; all.resolved += t.resolved; all.ambiguous += t.ambiguous; all.ambiguousTwinOnly += t.ambiguousTwinOnly
            all.unresolved += t.unresolved; all.targetsProject += t.targetsProject; all.targetsLibSys += t.targetsLibSys
            all.targetsLibUtilTwin += t.targetsLibUtilTwin; all.targetsLibOther += t.targetsLibOther
        }
        println("$tag ALL ${all.line()} resolveMs=$resolveMs")

        for ((kind, t) in perKind) {
            println("$tag top unresolved $kind (${t.unresolvedNames.size} distinct): ${top(t.unresolvedNames, 20)}")
        }
        println("$tag BARE unresolved examples: ${perKind.getValue(FantomReference.Kind.BARE).unresolvedExamples}")
        val slot = perKind.getValue(FantomReference.Kind.SLOT)
        println("$tag top ambiguous SLOT raw (name=occurrences/maxTargets): " +
            top(slot.ambiguousNames, 10).joinToString(", ") { (n, c) -> "$n=$c/${slot.ambiguousTargets[n]}" })
        println("$tag top ambiguous SLOT without project/library twins: " +
            top(slot.ambiguousNamesNoTwin, 10).joinToString(", ") { (n, c) -> "$n=$c/${slot.ambiguousTargetsNoTwin[n]}" })
        for ((kind, t) in perKind) println("$tag $kind target classes: ${t.targetClasses}")

        // Unresolved names that are index keys in allScope: a hint at resolver bugs.
        val scope = GlobalSearchScope.allScope(project)
        for ((kind, t) in perKind) {
            val wantType = kind == FantomReference.Kind.TYPE
            var shown = 0
            for ((n, _) in top(t.unresolvedNames, 40)) {
                val entries = FantomDeclarationIndex.entries(n, project, scope)
                if (entries.isEmpty()) continue
                val sameKind = entries.filter { it.kind.isType == wantType }
                val verdict = if (sameKind.isNotEmpty()) "<- BUG CANDIDATE" else "(other kind only, name clash)"
                println("$tag unresolved $kind '$n' x${t.unresolvedNames[n]} is an index key: ${entries.groupingBy { it.kind }.eachCount()} $verdict")
                if (++shown >= 15) break
            }
        }
        for ((kind, t) in perKind) {
            if (t.wrongKind.isNotEmpty()) println("$tag $kind refs with a target of the wrong kind: ${t.wrongKind.size}, e.g. ${t.wrongKind.take(5)}")
            if (t.suspectLocals.isNotEmpty()) println("$tag $kind suspect local targets: ${t.suspectLocals.size}, e.g. ${t.suspectLocals.take(8)}")
        }

        assertTrue("no references found in ${files.size} files", all.total > 0)
        assertTrue("resolve took ${resolveMs}ms, limit 120s", resolveMs < 120_000)
        if (failures.isNotEmpty()) fail("${failures.size} resolveAll calls threw:\n  ${failures.take(20).joinToString("\n  ")}")
        return perKind
    }

    /** Same name, same PSI class, same enclosing type: the project and library copy of one declaration. */
    private fun isTwin(a: FantomNamedElement, b: FantomNamedElement): Boolean =
        a.name == b.name && a.javaClass == b.javaClass &&
            FantomDeclarationIndex.enclosingTypeName(a) == FantomDeclarationIndex.enclosingTypeName(b)

    private fun lineOf(e: PsiElement): Int =
        (e.containingFile.viewProvider.document?.getLineNumber(e.textOffset) ?: -1) + 1

    private fun top(counts: Map<String, Int>, n: Int): List<Pair<String, Int>> =
        counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(n).map { it.key to it.value }

    // ---- 5: thresholds ----------------------------------------------------

    private fun thresholds(perKind: Map<FantomReference.Kind, Tally>) {
        val type = perKind.getValue(FantomReference.Kind.TYPE)
        val bare = perKind.getValue(FantomReference.Kind.BARE)
        val slot = perKind.getValue(FantomReference.Kind.SLOT)
        assertTrue("TYPE resolved ${type.pct(type.resolved)}% < 90%", type.pct(type.resolved) >= 90.0)
        assertTrue("BARE resolved ${bare.pct(bare.resolved)}% < 80%", bare.pct(bare.resolved) >= 80.0)
        assertTrue("TYPE targets of the wrong kind: ${type.wrongKind.take(5)}", type.wrongKind.isEmpty())
        assertTrue("SLOT targets of the wrong kind: ${slot.wrongKind.take(5)}", slot.wrongKind.isEmpty())
    }

    // ---- 6: guard -------------------------------------------------------

    private fun guardDeclarationNamesHaveNoReference() {
        val bad = ArrayList<String>()
        var checked = 0
        for ((name, psi) in files) {
            for (id in PsiTreeUtil.findChildrenOfType(psi, FantomId::class.java)) {
                if (id.parent !is FantomNamedElement) continue
                checked++
                if (id.reference != null) bad += "$name:${lineOf(id)} '${id.text}'"
            }
        }
        println("$tag declaration-name ids checked=$checked withReference=${bad.size}")
        assertTrue("no declaration names found", checked > 0)
        assertTrue("declaration names with a reference: ${bad.take(10)}", bad.isEmpty())
    }

    // ---- 5: spot checks -------------------------------------------------

    private fun spotChecks() {
        if ("JsonInStream.fan" !in files) {
            println("$tag spot checks skipped: JsonInStream.fan not loaded (fantom.pod.dir override)")
            return
        }
        val fileIndex = ProjectFileIndex.getInstance(project)

        // Local: `key` in `obj[key] = val` -> the `key := parseStr` local in parsePair().
        val key = resolveOne("JsonInStream.fan", "obj[key] = val", "key")
        assertTrue("key target is ${key.javaClass.simpleName}", key is FantomLocalDef)
        assertEquals("JsonInStream.fan", key.containingFile.name)
        assertFalse("key resolved into the library copy", fileIndex.isInLibrarySource(key.containingFile.virtualFile))
        assertEquals("key := parseStr", lineTextOf(key))

        // Bare call: `parseStr` in `key := parseStr` -> private Str parseStr() of the same class.
        val parseStr = resolveOne("JsonInStream.fan", "key := parseStr", "parseStr")
        assertTrue("parseStr target is ${parseStr.javaClass.simpleName}", parseStr is FantomMethodDef)
        assertEquals("JsonInStream.fan", parseStr.containingFile.name)
        assertFalse("parseStr resolved into the library copy", fileIndex.isInLibrarySource(parseStr.containingFile.virtualFile))
        assertEquals("private Str parseStr()", lineTextOf(parseStr))

        // Type: `Str` in `private Str parseStr()` -> class Str in the library's Str.fan.
        val str = resolveOne("JsonInStream.fan", "private Str parseStr()", "Str")
        assertTrue("Str target is ${str.javaClass.simpleName}", str is FantomClassDef)
        assertEquals("Str.fan", str.containingFile.name)
        assertTrue("Str.fan is not a library source", fileIndex.isInLibrarySource(str.containingFile.virtualFile))
    }

    private fun lineTextOf(e: PsiElement): String {
        val doc = e.containingFile.viewProvider.document ?: error("no document")
        val line = doc.getLineNumber(e.textOffset)
        return doc.text.substring(doc.getLineStartOffset(line), doc.getLineEndOffset(line)).trim()
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
        println("$tag spot $fileName '$idText' ${ref.kind} -> ${targets.map { "${it.javaClass.simpleName.removeSuffix("Impl")}@${it.containingFile.name}:${lineOf(it)}" }}")
        assertEquals("$fileName '$idText' (${ref.kind}) targets: $targets", 1, targets.size)
        return targets[0]
    }
}
