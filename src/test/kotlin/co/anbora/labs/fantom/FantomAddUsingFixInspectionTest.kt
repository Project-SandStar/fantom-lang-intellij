package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.inspections.FantomUnresolvedReferenceInspection
import co.anbora.labs.fantom.lang.library.FantomSettings
import java.io.File
import java.nio.file.Files

/**
 * The "Add using pod::Name" fix of [FantomUnresolvedReferenceInspection]. Uses a fake
 * Fantom home (a `bin/fan` file and one pod source) that is never indexed, so the
 * type name stays unresolved and the fix is offered. Needs no real install.
 */
class FantomAddUsingFixInspectionTest : FantomInspectionTestBase() {

    private lateinit var fakeHome: File

    override fun setUp() {
        super.setUp()
        fakeHome = Files.createTempDirectory("fantom-fake-home").toFile()
        File(fakeHome, "bin").mkdirs()
        File(fakeHome, "bin/fan").writeText("#!/bin/sh\n")
        File(fakeHome, "src/zzpod/fan").mkdirs()
        File(fakeHome, "src/zzpod/fan/Zzqx.fan").writeText("class Zzqx {}\n")
        FantomUnresolvedReferenceInspection.UsingCandidates.reset()
        FantomSettings.getInstance(project).homePath = fakeHome.path
        myFixture.enableInspections(FantomUnresolvedReferenceInspection())
    }

    override fun tearDown() {
        try {
            FantomSettings.getInstance(project).homePath = null
            FantomUnresolvedReferenceInspection.UsingCandidates.reset()
            fakeHome.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testUnresolvedTypeIsReported() {
        checkWarnings("class A { <warning descr=\"Cannot resolve symbol 'Zzqx'\">Zzqx</warning>? f }")
    }

    fun testAddUsingAtFileTop() {
        myFixture.configureByText("A.fan", "class A { Zz<caret>qx? f }")
        myFixture.launchAction(myFixture.findSingleIntention("Add using"))
        myFixture.checkResult("using zzpod::Zzqx\nclass A { Zzqx? f }")
    }

    fun testAddUsingAfterLastUsing() {
        myFixture.configureByText("A.fan", "using sys\nusing concurrent\n\nclass A { Zz<caret>qx? f }")
        myFixture.launchAction(myFixture.findSingleIntention("Add using zzpod::Zzqx"))
        myFixture.checkResult("using sys\nusing concurrent\nusing zzpod::Zzqx\n\nclass A { Zzqx? f }")
    }

    fun testNoFixForTypeWithoutSourceFile() {
        myFixture.configureByText("A.fan", "class A { Zz<caret>other? f }")
        assertEmpty(myFixture.filterAvailableIntentions("Add using"))
    }
}
