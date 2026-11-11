package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex.Entry
import co.anbora.labs.fantom.lang.index.FantomDeclarationKind
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Builds [FantomDeclarationIndex] over the whole Fantom `sys` pod and checks
 * the index is right on real code and fast enough. Skips when the Fantom
 * source tree is not on this machine.
 */
class FantomDeclarationIndexCorpusTest : BasePlatformTestCase() {

    private val sysDir = File(System.getProperty("fantom.sys.dir", "/Users/alper/fantom/fantom-1.0.84/src/sys/fan"))
    private val tag = "FantomDeclarationIndexCorpusTest:"

    fun testSysPodIndex() {
        if (!sysDir.isDirectory) {
            println("$tag $sysDir missing, skipped")
            return
        }
        val files = sysDir.listFiles { f -> f.isFile && f.extension == "fan" }?.sortedBy { it.name }.orEmpty()
        assertFalse("no .fan files in $sysDir", files.isEmpty())

        val addStart = System.nanoTime()
        for (f in files) {
            myFixture.addFileToProject("sys/fan/${f.name}", StringUtil.convertLineSeparators(f.readText()))
        }
        val addMs = (System.nanoTime() - addStart) / 1_000_000
        val scope = GlobalSearchScope.projectScope(project)

        // The platform may index while files are added, so the build cost is
        // bounded by add time plus the first query, not the query alone.
        val queryStart = System.nanoTime()
        val str = FantomDeclarationIndex.entries("Str", project, scope)
        val queryMs = (System.nanoTime() - queryStart) / 1_000_000
        val buildMs = addMs + queryMs
        println("$tag files=${files.size} addFilesMs=$addMs firstQueryMs=$queryMs addPlusFirstQueryMs=$buildMs")
        assertTrue("index build took ${buildMs}ms, limit 60s", buildMs < 60_000)

        // Str: exactly one class.
        assertEquals("Str entries: $str", 1, str.count { it.kind == FantomDeclarationKind.CLASS })

        // toStr: many methods on distinct owners, Obj among them.
        val toStr = FantomDeclarationIndex.entries("toStr", project, scope)
        val toStrMethods = toStr.filter { it.kind == FantomDeclarationKind.METHOD }
        val toStrOwners = toStrMethods.mapNotNull { it.ownerType }.toSet()
        println("$tag toStr methods=${toStrMethods.size} owners=${toStrOwners.size} nonMethod=${toStr.size - toStrMethods.size}")
        assertTrue("toStr METHOD entries: ${toStrMethods.size}", toStrMethods.size >= 20)
        assertTrue("toStr distinct owners: ${toStrOwners.size}", toStrOwners.size >= 20)
        assertTrue("toStr owners lack Obj: $toStrOwners", "Obj" in toStrOwners)

        // make: constructors.
        val make = FantomDeclarationIndex.entries("make", project, scope)
        val makeCtors = make.filter { it.kind == FantomDeclarationKind.CTOR }
        println("$tag make ctors=${makeCtors.size} other=${make.size - makeCtors.size}")
        assertTrue("make has no CTOR entries: $make", makeCtors.isNotEmpty())

        // allNames: well known types present, every key identifier shaped.
        val allNames = FantomDeclarationIndex.allNames(project)
        for (n in listOf("List", "Map", "Func", "Err", "Duration")) {
            assertTrue("allNames lacks $n", n in allNames)
        }
        val badNames = allNames.filter { it.isEmpty() || it.any { c -> c.isWhitespace() } }
        assertTrue("bad names in index: $badNames", badNames.isEmpty())

        // findDeclarations: PSI for Str lives in Str.fan.
        val strTypes = FantomDeclarationIndex.findDeclarations("Str", project, scope) { it.isType }
        assertEquals("Str type declarations: ${strTypes.map { it.containingFile.name }}", 1, strTypes.size)
        assertEquals("Str.fan", strTypes.single().containingFile.name)

        report(allNames, scope)
    }

    /** Prints counts and anything that looks wrong. Diagnostics only; no asserts. */
    private fun report(allNames: Collection<String>, scope: GlobalSearchScope) {
        val byName = allNames.associateWith { FantomDeclarationIndex.entries(it, project, scope) }
            .filterValues { it.isNotEmpty() }
        val entries = byName.values.flatten()
        val kinds = entries.groupingBy { it.kind }.eachCount().toSortedMap()
        val typeNames = byName.filterValues { v -> v.any { it.kind.isType } }.keys
        println("$tag namesInScope=${byName.size} entries=${entries.size} types=${typeNames.size} kinds=$kinds")

        val slotsNoOwner = byName.filterValues { v -> v.any { !it.kind.isType && it.ownerType == null } }.keys
        if (slotsNoOwner.isNotEmpty()) println("$tag slots without owner: ${slotsNoOwner.sorted()}")

        val unknownOwners = entries.mapNotNull { it.ownerType }.toSet() - typeNames
        if (unknownOwners.isNotEmpty()) println("$tag owners that are not indexed types: ${unknownOwners.sorted()}")

        val duplicateTypes = byName.filter { (_, v) -> v.count { it.kind.isType } > 1 }
            .mapValues { (_, v) -> v.filter { it.kind.isType } }
        if (duplicateTypes.isNotEmpty()) println("$tag names with more than one type entry: $duplicateTypes")

        val duplicateSlots = byName.mapValues { (_, v) ->
            v.filter { !it.kind.isType }.groupingBy { it }.eachCount().filterValues { c -> c > 1 }
        }.filterValues { it.isNotEmpty() }
        if (duplicateSlots.isNotEmpty()) {
            println("$tag duplicate (kind, owner) under one name: ${duplicateSlots.size}, e.g. ${duplicateSlots.entries.take(5)}")
        }

        val lowerTypes = typeNames.filter { it.first().isLowerCase() }
        if (lowerTypes.isNotEmpty()) println("$tag lower case type names: ${lowerTypes.sorted()}")
        val upperSlots = byName.filter { (n, v) -> n.first().isUpperCase() && v.any { !it.kind.isType } }.keys
        if (upperSlots.isNotEmpty()) println("$tag upper case slot names: ${upperSlots.sorted()}")
    }
}
