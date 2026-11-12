package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.core.psi.FantomCall
import co.anbora.labs.fantom.lang.core.psi.FantomDotCall
import co.anbora.labs.fantom.lang.core.psi.FantomField
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomId
import co.anbora.labs.fantom.lang.core.psi.FantomIdExpr
import co.anbora.labs.fantom.lang.core.psi.FantomLocal
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomSafeDotCallStm
import co.anbora.labs.fantom.lang.core.psi.FantomSlotExpr
import co.anbora.labs.fantom.lang.core.psi.FantomTermChain
import co.anbora.labs.fantom.lang.core.psi.FantomTermExpr
import co.anbora.labs.fantom.lang.core.psi.FantomTypes
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomSettings
import co.anbora.labs.fantom.lang.resolve.FantomReference
import co.anbora.labs.fantom.lang.resolve.FantomScope
import co.anbora.labs.fantom.lang.resolve.FantomSlotLookup
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.application.WriteAction
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
 * Measures basic completion on the `util` pod with the whole Fantom install as a library.
 *
 * For a sample of real `recv.slot` uses and bare-name uses the test cuts the identifier
 * to its first letter, runs Ctrl+Space there and records whether the original name is
 * offered, at which rank, and how long the list is. Read-only on `src/main`; skips when
 * no Fantom home is on this machine.
 *
 * The simulated copy is a second project file named [SIM_FILE]; its types duplicate the
 * loaded copy, which is neutral for slot names.
 *
 * One-letter names are skipped: cutting them to the first letter changes nothing, so a
 * null lookup could not tell a hit from a miss.
 *
 * Sites are collected cheaply, sampled (every Nth), and only the sample is classified
 * (receiver type, slot on chain) so the 240 s budget goes to completion itself.
 *
 * Env: `FANTOM_COMPLETION_SLOTS` (default 150) and `FANTOM_COMPLETION_BARE` (default 60)
 * cap the samples. A cap of 20 or less prints every site.
 */
class FantomCompletionCorpusTest : BasePlatformTestCase() {

    private val tag = "FantomCompletionCorpusTest:"
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

    /** One completion site, captured as plain values before any simulation runs. */
    private class Site(
        val fileName: String,
        val text: String,
        val line: Int,
        val offset: Int,
        val name: String,
        val receiverText: String,
        val receiverType: String?,
        /** Why the site could miss: see [cause]. */
        val cause: String,
    )

    private class Outcome(
        val site: Site,
        val hit: Boolean,
        val rank: Int,
        val size: Int,
        val offered: List<String>,
    )

    fun testUtilPodCompletion() {
        if (home == null) {
            println("$tag skipped: no Fantom home found")
            return
        }
        val podDir = File(home, "src/util/fan")
        assertTrue("$podDir is not a folder", podDir.isDirectory)
        val sources = podDir.listFiles { f -> f.isFile && f.extension == "fan" }?.sortedBy { it.name }.orEmpty()
        assertFalse("no .fan files in $podDir", sources.isEmpty())
        for (f in sources) {
            files[f.name] = myFixture.addFileToProject("util/fan/${f.name}", StringUtil.convertLineSeparators(f.readText()))
        }
        println("$tag files=${files.size} home=$home")

        val slotCap = System.getenv("FANTOM_COMPLETION_SLOTS")?.toIntOrNull() ?: 150
        val bareCap = System.getenv("FANTOM_COMPLETION_BARE")?.toIntOrNull() ?: 60
        val verbose = slotCap <= 20 || bareCap <= 20
        val failures = ArrayList<String>()

        val start = System.nanoTime()
        val slotCandidates = collectSlotCandidates()
        val bareCandidates = collectBareCandidates()
        println("$tag collected slotSites=${slotCandidates.size} bareSites=${bareCandidates.size} " +
            "collectMs=${(System.nanoTime() - start) / 1_000_000}")
        val classifyStart = System.nanoTime()
        val slotSites = sample(slotCandidates, slotCap).map { slotSite(it) }
        val bareSites = sample(bareCandidates, bareCap).map { bareSite(it) }
        println("$tag sampled slotSites=${slotSites.size} bareSites=${bareSites.size} " +
            "classifyMs=${(System.nanoTime() - classifyStart) / 1_000_000}")
        val slotStart = System.nanoTime()
        val slotOutcomes = ArrayList<Outcome>()
        for (s in slotSites) {
            try {
                slotOutcomes += simulate(s)
            } catch (e: Throwable) {
                failures += "${s.fileName}:${s.line} '${s.name}': $e"
            }
        }
        val slotMs = (System.nanoTime() - slotStart) / 1_000_000
        report("SLOT", slotOutcomes, slotMs, verbose)

        val bareStart = System.nanoTime()
        val bareOutcomes = ArrayList<Outcome>()
        for (s in bareSites) {
            try {
                bareOutcomes += simulate(s)
            } catch (e: Throwable) {
                failures += "${s.fileName}:${s.line} '${s.name}': $e"
            }
        }
        val bareMs = (System.nanoTime() - bareStart) / 1_000_000
        report("BARE", bareOutcomes, bareMs, verbose)

        val wallMs = (System.nanoTime() - start) / 1_000_000
        println("$tag total wallMs=$wallMs")

        if (failures.isNotEmpty()) fail("${failures.size} sites threw:\n  ${failures.take(20).joinToString("\n  ")}")
        assertTrue("run took ${wallMs}ms, limit 240s", wallMs < 240_000)
        assertFalse("no receiver-slot sites sampled", slotOutcomes.isEmpty())
        assertFalse("no bare-name sites sampled", bareOutcomes.isEmpty())

        val slotHit = rate(slotOutcomes) { it.hit }
        val slotTop10 = rate(slotOutcomes) { it.hit && it.rank < 10 }
        val bareHit = rate(bareOutcomes) { it.hit }
        assertTrue("receiver-slot hit rate $slotHit% < 85%", slotHit >= 85.0)
        assertTrue("receiver-slot top-10 rate $slotTop10% < 70%", slotTop10 >= 70.0)
        assertTrue("bare-name hit rate $bareHit% < 90%", bareHit >= 90.0)
    }

    // ---- sites -------------------------------------------------------------

    /** A `recv.slot` id and its chain operator, before any type work. */
    private class SlotCandidate(val fileName: String, val id: FantomId, val op: PsiElement)

    /** A bare id and the declarations it resolves to, before any type work. */
    private class BareCandidate(val fileName: String, val id: FantomId, val targets: List<PsiElement>)

    /** Every `recv.slot` and `recv?.slot` id in the loaded files (names of 2+ chars). */
    private fun collectSlotCandidates(): List<SlotCandidate> {
        val out = ArrayList<SlotCandidate>()
        for ((fileName, psi) in files) {
            for (id in PsiTreeUtil.findChildrenOfType(psi, FantomId::class.java)) {
                val owner = id.parent
                if (owner !is FantomField && owner !is FantomCall) continue
                val slotExpr = owner.parent as? FantomSlotExpr ?: continue
                val op = slotExpr.parent ?: continue
                if (op !is FantomDotCall && op !is FantomSafeDotCallStm) continue
                if (!usableName(id.text)) continue
                out += SlotCandidate(fileName, id, op)
            }
        }
        return out
    }

    /** Every bare name (not after a dot) that resolves to a local, param or field. */
    private fun collectBareCandidates(): List<BareCandidate> {
        val out = ArrayList<BareCandidate>()
        for ((fileName, psi) in files) {
            for (local in PsiTreeUtil.findChildrenOfType(psi, FantomLocal::class.java)) {
                if (local.parent !is FantomIdExpr) continue
                val id = PsiTreeUtil.getChildOfType(local, FantomId::class.java) ?: continue
                if (!usableName(id.text)) continue
                val ref = id.reference as? FantomReference ?: continue
                if (ref.kind != FantomReference.Kind.BARE) continue
                val targets = ref.resolveAll()
                if (targets.isEmpty()) continue
                if (!targets.all { FantomScope.isLocalLike(it) || it is FantomFieldDef }) continue
                out += BareCandidate(fileName, id, targets)
            }
        }
        return out
    }

    private fun usableName(name: String): Boolean =
        name.length >= 2 && (name[0].isLetter() || name[0] == '_')

    private fun slotSite(c: SlotCandidate): Site {
        val id = c.id
        val op = c.op
        val name = id.text
        val recvText = receiverText(op)
        val recvType = FantomTypeInference.receiverType(op)
        val cause = when {
            isThisOrSuper(op) -> "D this/super receiver"
            recvType == null -> "A receiver type unknown"
            !slotOnChain(recvType.slotOwnerName, name) -> "B slot not on chain of ${recvType.presentableText}"
            else -> "C on chain"
        }
        return Site(c.fileName, id.containingFile.text, lineOf(id), id.textRange.startOffset, name, recvText, recvType?.presentableText, cause)
    }

    private fun bareSite(c: BareCandidate): Site {
        val id = c.id
        val first = c.targets.first()
        val kind = first.javaClass.simpleName.removeSuffix("Impl").removePrefix("Fantom")
        val type = (first as? FantomNamedElement)?.let { FantomTypeInference.typeOfDeclaration(it)?.presentableText }
        return Site(c.fileName, id.containingFile.text, lineOf(id), id.textRange.startOffset, id.text, "-> $kind", type, "resolves to $kind")
    }

    /** Every Nth site so at most [cap] remain, spread over the whole list. */
    private fun <T> sample(all: List<T>, cap: Int): List<T> {
        if (all.size <= cap) return all
        return (0 until cap).map { k -> all[(k.toLong() * all.size / cap).toInt()] }
    }

    // ---- simulation --------------------------------------------------------

    private fun simulate(site: Site): Outcome {
        val cut = site.text.substring(0, site.offset + 1) + "<caret>" + site.text.substring(site.offset + site.name.length)
        myFixture.configureByText(SIM_FILE, cut)
        val items = myFixture.complete(CompletionType.BASIC)
        try {
            if (items == null) {
                // single item auto-inserted, or nothing offered
                val doc = myFixture.editor.document.text
                val inserted = identifierAt(doc, site.offset)
                val typed = site.name.substring(0, 1)
                return if (inserted == site.name) Outcome(site, true, 0, 1, listOf(inserted))
                else if (inserted == typed) Outcome(site, false, -1, 0, emptyList())
                else Outcome(site, false, -1, 1, listOf(inserted))
            }
            val names = items.map { it.lookupString }
            val rank = names.indexOf(site.name)
            return Outcome(site, rank >= 0, rank, names.size, names.take(5))
        } finally {
            LookupManager.getInstance(project).hideActiveLookup()
        }
    }

    private fun identifierAt(text: String, offset: Int): String {
        var end = offset
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
        return text.substring(offset, end)
    }

    // ---- report ------------------------------------------------------------

    private fun report(label: String, outcomes: List<Outcome>, wallMs: Long, verbose: Boolean) {
        val n = outcomes.size
        println("$tag $label sampled=$n wallMs=$wallMs")
        if (n == 0) return
        val sizes = outcomes.map { it.size }.sorted()
        val median = if (n % 2 == 1) sizes[n / 2].toDouble() else (sizes[n / 2 - 1] + sizes[n / 2]) / 2.0
        println("$tag $label hit=${pct(rate(outcomes) { it.hit })} top3=${pct(rate(outcomes) { it.hit && it.rank < 3 })} " +
            "top10=${pct(rate(outcomes) { it.hit && it.rank < 10 })} medianSize=$median meanSize=${"%.1f".format(sizes.average())}")
        val byCause = sortedMapOf<String, IntArray>()
        for (o in outcomes) {
            val c = byCause.getOrPut(o.site.cause.substringBefore(" of ")) { intArrayOf(0, 0) }
            c[0]++
            if (o.hit) c[1]++
        }
        println("$tag $label by cause (total/hit): " + byCause.entries.joinToString("; ") { "${it.key}=${it.value[0]}/${it.value[1]}" })
        if (verbose) {
            for (o in outcomes) println("$tag $label   ${row(o)}")
        }
        val worst = outcomes.filter { !it.hit || it.rank > 10 }
            .sortedWith(compareBy<Outcome> { it.hit }.thenByDescending { it.rank })
        println("$tag $label worst sites (${worst.size} of $n; showing ${minOf(15, worst.size)}):")
        for (o in worst.take(15)) println("$tag $label   ${row(o)}")
    }

    private fun row(o: Outcome): String =
        "${o.site.fileName}:${o.site.line} '${o.site.name}' hit=${o.hit} rank=${o.rank} size=${o.size} " +
            "recv=`${o.site.receiverText}` type=${o.site.receiverType} cause=[${o.site.cause}] offered=${o.offered}"

    private fun rate(outcomes: List<Outcome>, pred: (Outcome) -> Boolean): Double =
        if (outcomes.isEmpty()) 0.0 else 100.0 * outcomes.count(pred) / outcomes.size

    private fun pct(d: Double) = "%.1f%%".format(d)

    // ---- psi helpers -------------------------------------------------------

    private fun receiverText(op: PsiElement): String {
        val step = op.parent as? FantomTermChain ?: return "?"
        val container = step.parent ?: return "?"
        val text = container.text.substring(0, op.textRange.startOffset - container.textRange.startOffset)
        return text.replace(Regex("\\s+"), " ").trim().take(60)
    }

    private fun isThisOrSuper(op: PsiElement): Boolean {
        val step = op.parent as? FantomTermChain ?: return false
        val term = step.parent as? FantomTermExpr ?: return false
        if (term.termChainList.firstOrNull() != step) return false
        val lit = term.termBase?.literal ?: return false
        return lit.node.findChildByType(FantomTypes.THIS) != null || lit.node.findChildByType(FantomTypes.SUPER) != null
    }

    private fun slotOnChain(typeName: String, slot: String): Boolean =
        FantomSlotLookup.allSlotsOfTypeName(typeName, project, GlobalSearchScope.allScope(project)).any { it.name == slot }

    private fun lineOf(e: PsiElement): Int =
        (e.containingFile.viewProvider.document?.getLineNumber(e.textOffset) ?: -1) + 1

    companion object {
        private const val SIM_FILE = "CompletionSim.fan"
    }
}
