package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.core.psi.*
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomSettings
import co.anbora.labs.fantom.lang.resolve.FantomReference
import co.anbora.labs.fantom.lang.resolve.FantomScope
import co.anbora.labs.fantom.lang.types.FanType
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import java.io.File

/**
 * Measures what [FantomTypeInference] buys slot resolution on the `util` pod (project
 * files) with the whole Fantom install as a library, and where it is blind.
 *
 * 1. Every SLOT reference is put in one of four groups: narrowed by the inferred
 *    receiver type, narrowed by `this`/`super`/a static owner type, receiver type known
 *    but no slot of that name on its type chain (so it fell back), and plain name lookup.
 * 2. `typeOf` over every expression statement and every `:=` local: known or null, and
 *    the shapes of the null cases.
 * 3. Spot checks on real util lines.
 *
 * Read-only measurement plus asserts. Skips when no Fantom home is on this machine.
 */
class FantomTypedResolveCorpusTest : BasePlatformTestCase() {

    private val tag = "FantomTypedResolveCorpusTest:"
    private var home: File? = null
    private val files = LinkedHashMap<String, PsiFile>()
    private val chainCache = HashMap<String, Set<String>>()

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

    private enum class Group(val label: String) {
        TYPED("narrowed by receiver type"),
        OWNER("narrowed by this/super/static owner"),
        TYPED_MISS("receiver type known, no slot on its chain (fell back)"),
        PLAIN("plain name lookup (fallback)"),
    }

    /** Where a SLOT id sits: the chain operator (`.x` etc.), its step and container, or a static owner. */
    private class Site(
        val chainOp: PsiElement?,
        val step: FantomTermChain?,
        val container: PsiElement?,
        val thisOrSuper: Boolean,
        val staticOwner: String?,
        val receiverText: String,
    )

    fun testUtilPodTypedResolve() {
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

        val failures = ArrayList<String>()
        val start = System.nanoTime()
        slotTally(failures)
        typeTally(failures)
        val wallMs = (System.nanoTime() - start) / 1_000_000
        println("$tag tally wallMs=$wallMs")
        if (failures.isNotEmpty()) fail("${failures.size} calls threw:\n  ${failures.take(20).joinToString("\n  ")}")
        assertTrue("tally took ${wallMs}ms, limit 180s", wallMs < 180_000)

        spotChecks()
    }

    // ---- 1: SLOT references by narrowing group ----------------------------

    private fun slotTally(failures: MutableList<String>) {
        val counts = Group.entries.associateWith { intArrayOf(0, 0) } // total, exactly one target
        val fallbackNames = HashMap<String, Int>()
        val fallbackExample = HashMap<String, String>()
        val fallbackClasses = sortedMapOf<String, Int>()
        val missExamples = ArrayList<String>()
        val typedAmbiguous = ArrayList<String>()
        val typedZero = ArrayList<String>()

        for ((fileName, psi) in files) {
            for (id in PsiTreeUtil.findChildrenOfType(psi, FantomId::class.java)) {
                val ref = id.reference as? FantomReference ?: continue
                if (ref.kind != FantomReference.Kind.SLOT) continue
                val where = "$fileName:${lineOf(id)}"
                try {
                    val targets = ref.resolveAll()
                    val site = siteOf(id)
                    val owners = targets.map { FantomDeclarationIndex.enclosingTypeName(it) }
                    val receiverType = site.chainOp?.let { FantomTypeInference.receiverType(it) }
                    val group = when {
                        site.thisOrSuper -> Group.OWNER
                        site.staticOwner != null && targets.isNotEmpty() &&
                            owners.all { it != null && it in chainOf(site.staticOwner) } -> Group.OWNER
                        receiverType != null && targets.isNotEmpty() &&
                            owners.all { it != null && it in chainOf(receiverType.slotOwnerName) } -> Group.TYPED
                        receiverType != null -> Group.TYPED_MISS
                        else -> Group.PLAIN
                    }
                    val c = counts.getValue(group)
                    c[0]++
                    if (targets.size == 1) c[1]++
                    if (group == Group.PLAIN || group == Group.TYPED_MISS) {
                        val cls = classifyReceiver(site)
                        fallbackClasses.merge(cls, 1, Int::plus)
                        fallbackNames.merge(ref.name, 1, Int::plus)
                        fallbackExample.putIfAbsent(ref.name, "$where recv=`${site.receiverText}` [$cls] targets=${targets.size}")
                    }
                    if (group == Group.TYPED_MISS && missExamples.size < 40) {
                        missExamples += "$where '${ref.name}' recv=`${site.receiverText}`:${receiverType!!.presentableText} -> ${targets.size} targets in ${owners.distinct()}"
                    }
                    if (group == Group.TYPED && targets.size > 1 && typedAmbiguous.size < 20) {
                        typedAmbiguous += "$where '${ref.name}' on ${receiverType!!.presentableText} -> " +
                            targets.map { "${kindOf(it)}@${it.containingFile.name}:${lineOf(it)}" }
                    }
                    if (group == Group.OWNER && targets.isEmpty() && typedZero.size < 10) {
                        typedZero += "$where '${ref.name}' recv=`${site.receiverText}`"
                    }
                } catch (e: Throwable) {
                    failures += "$where SLOT '${ref.name}': $e"
                }
            }
        }

        val total = counts.values.sumOf { it[0] }
        println("$tag SLOT total=$total")
        for ((g, c) in counts) {
            println("$tag SLOT ${g.label}: count=${c[0]} exactlyOne=${c[1]}")
        }
        val fallbackTotal = counts.getValue(Group.PLAIN)[0] + counts.getValue(Group.TYPED_MISS)[0]
        println("$tag SLOT fallback receivers by syntactic class ($fallbackTotal): $fallbackClasses")
        println("$tag SLOT top 20 names that still fall back:")
        for ((n, cnt) in top(fallbackNames, 20)) println("$tag   $n x$cnt  ${fallbackExample[n]}")
        println("$tag SLOT receiver type known but no slot on its chain (${missExamples.size} shown):")
        for (m in missExamples) println("$tag   $m")
        println("$tag SLOT typed but still >1 target (${typedAmbiguous.size} shown):")
        for (m in typedAmbiguous) println("$tag   $m")
        if (typedZero.isNotEmpty()) println("$tag SLOT this/super receiver with 0 targets: $typedZero")

        assertTrue("no SLOT references found", total > 0)
    }

    private fun siteOf(id: FantomId): Site {
        val parent = id.parent
        if (parent is FantomTypeLiteral) return Site(null, null, null, false, null, oneLine(parent.text))
        // `@Facet { x = 1 }`: the facet type owns the name, like `Type.x`.
        if (parent is co.anbora.labs.fantom.lang.core.psi.FantomFacetVal) {
            val facet = (parent.parent?.parent as? co.anbora.labs.fantom.lang.core.psi.FantomFacetItems)?.simpleType?.idList?.lastOrNull()?.text
            return Site(null, null, null, false, facet, "@$facet")
        }
        val slotExpr = parent?.parent as? FantomSlotExpr ?: return Site(null, null, null, false, null, "?")
        val op = slotExpr.parent ?: return Site(null, null, null, false, null, "?")
        if (op is FantomStaticCall) {
            return Site(null, null, null, false, op.type.simpleType?.idList?.lastOrNull()?.text, oneLine(op.type.text))
        }
        val step = op.parent as? FantomTermChain ?: return Site(null, null, null, false, null, "?")
        val container = step.parent ?: return Site(op, step, null, false, null, "?")
        val text = container.text.substring(0, op.textRange.startOffset - container.textRange.startOffset)
        val thisOrSuper = container is FantomTermExpr && container.termChainList.firstOrNull() == step &&
            container.termBase?.literal?.let { l ->
                l.node.findChildByType(FantomTypes.THIS) != null || l.node.findChildByType(FantomTypes.SUPER) != null
            } == true
        return Site(op, step, container, thisOrSuper, null, oneLine(text))
    }

    /** Short names of [typeName] and every supertype, declared and implicit, as the resolver walks them. */
    private fun chainOf(typeName: String): Set<String> = chainCache.getOrPut(typeName) {
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque<String>().apply { add(typeName) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (!seen.add(n)) continue
            for (t in FantomTypeInference.typeDefinitions(n, project)) {
                queue += FantomScope.superTypeNames(t)
                queue += FantomScope.implicitSuperTypeNames(t)
            }
        }
        seen
    }

    // Syntactic class of the receiver expression of a `recv.x` site.
    private fun classifyReceiver(site: Site): String {
        if (site.staticOwner != null) return "static type"
        val step = site.step ?: return "type literal"
        val container = site.container
        val chains = when (container) {
            is FantomTermExpr -> container.termChainList
            is FantomGroupedExpr -> container.termChainList
            else -> return "other"
        }
        val idx = chains.indexOf(step)
        if (idx > 0) return classifyChain(chains[idx - 1])
        return when (container) {
            is FantomTermExpr -> classifyBase(container.termBase)
            is FantomGroupedExpr -> if (firstMeaningful(container.expr) is FantomCastExpr) "cast" else "grouped expr"
            else -> "other"
        }
    }

    private fun classifyChain(c: FantomTermChain): String {
        c.dotCall?.let { return slotClass(it.slotExpr) }
        c.indexExpr?.let { return "index" }
        c.callOp?.let { return "call result" }
        c.itBlock?.let { return "it-block result" }
        return when (val s = c.stmt) {
            is FantomSafeDotCallStm -> slotClass(s.slotExpr)
            is FantomDynCallStm, is FantomSafeDynCallStm -> "dyn call result"
            else -> "other"
        }
    }

    private fun slotClass(slotExpr: FantomSlotExpr?): String {
        val id = slotExpr?.field?.id ?: slotExpr?.call?.id ?: return "other"
        val targets = (id.reference as? FantomReference)?.resolveAll().orEmpty()
        return when {
            targets.isEmpty() -> "unresolved slot"
            targets.all { it is FantomFieldDef } -> "field"
            else -> "method call result"
        }
    }

    private fun classifyBase(base: FantomTermBase?): String {
        base ?: return "other"
        base.literal?.let { l ->
            return when {
                l.node.findChildByType(FantomTypes.IT) != null -> "it"
                l.node.findChildByType(FantomTypes.THIS) != null -> "this"
                l.node.findChildByType(FantomTypes.SUPER) != null -> "super"
                l.str != null -> "str literal"
                else -> "literal"
            }
        }
        base.typeBase?.let { tb ->
            return when {
                tb.simple != null -> "ctor call"
                tb.staticCall != null -> "static call result"
                tb.typeLiteral != null -> "type literal"
                tb.closure != null -> "closure"
                tb.ctorBlock != null -> "ctor it-block"
                tb.dsl != null -> "dsl"
                else -> "super"
            }
        }
        val idExpr = base.idExpr ?: return "other"
        idExpr.slotExpr?.let { return slotClass(it) }
        val id = idExpr.local?.id ?: return "other"
        val targets = (id.reference as? FantomReference)?.resolveAll().orEmpty()
        val t = targets.singleOrNull() ?: return if (targets.isEmpty()) "unresolved name" else "ambiguous name"
        return when (t) {
            is FantomClosureParam -> if (t.type != null) "closure param (typed)" else "closure param"
            is FantomParam -> "param"
            is FantomCatchDef -> "catch param"
            is FantomLocalDef -> when {
                t.type != null -> "local (declared type)"
                initializerIsCall(t.expr) -> "local := call"
                else -> "local := other"
            }
            is FantomFieldDef -> "field"
            is FantomMethodDef, is FantomCtorDef -> "method call result"
            else -> "other(${kindOf(t)})"
        }
    }

    private fun initializerIsCall(expr: PsiElement?): Boolean {
        val term = firstMeaningful(expr) as? FantomTermExpr ?: return false
        if (term.termChainList.any { it.dotCall != null || it.callOp != null || it.stmt != null }) return true
        val base = term.termBase ?: return false
        base.typeBase?.let { return it.simple != null || it.staticCall != null }
        val id = base.idExpr?.local?.id ?: base.idExpr?.slotExpr?.call?.id ?: base.idExpr?.slotExpr?.field?.id ?: return false
        val t = (id.reference as? FantomReference)?.resolveAll().orEmpty().singleOrNull() ?: return false
        return t is FantomMethodDef || t is FantomCtorDef
    }

    /** Walks down single-child wrappers (`expr > assignExpr > ... > termExpr`). */
    private fun firstMeaningful(e: PsiElement?): PsiElement? {
        var node = e ?: return null
        while (true) {
            val kids = node.children
            if (kids.size != 1) return node
            node = kids[0]
        }
    }

    // ---- 2: typeOf over expression statements and `:=` locals -------------

    private fun typeTally(failures: MutableList<String>) {
        var exprKnown = 0
        var exprNull = 0
        var localKnown = 0
        var localNull = 0
        var localDeclared = 0
        val nullShapes = HashMap<String, Int>()
        val nullExample = HashMap<String, String>()
        val knownTypes = HashMap<String, Int>()

        fun record(fileName: String, e: PsiElement, t: FanType?): Boolean {
            if (t != null) {
                knownTypes.merge(t.presentableText, 1, Int::plus)
                return true
            }
            val s = shape(e)
            nullShapes.merge(s, 1, Int::plus)
            nullExample.putIfAbsent(s, "$fileName:${lineOf(e)} `${oneLine(e.text)}`")
            return false
        }

        for ((fileName, psi) in files) {
            for (stmt in PsiTreeUtil.findChildrenOfType(psi, FantomExprStmt::class.java)) {
                val e = stmt.expr ?: continue
                try {
                    if (record(fileName, e, FantomTypeInference.typeOf(e))) exprKnown++ else exprNull++
                } catch (ex: Throwable) {
                    failures += "$fileName:${lineOf(e)} typeOf(exprStmt): $ex"
                }
            }
            for (def in PsiTreeUtil.findChildrenOfType(psi, FantomLocalDef::class.java)) {
                if (def.type != null) {
                    localDeclared++
                    continue
                }
                val e = def.expr ?: continue
                try {
                    if (record(fileName, e, FantomTypeInference.typeOf(e))) localKnown++ else localNull++
                } catch (ex: Throwable) {
                    failures += "$fileName:${lineOf(e)} typeOf(localDef): $ex"
                }
            }
        }

        println("$tag typeOf exprStmts: known=$exprKnown null=$exprNull")
        println("$tag typeOf `:=` locals: known=$localKnown null=$localNull (declared-type locals skipped: $localDeclared)")
        println("$tag typeOf top known types: ${top(knownTypes, 15)}")
        println("$tag typeOf top 20 null shapes (${nullShapes.size} distinct):")
        for ((s, cnt) in top(nullShapes, 20)) println("$tag   x$cnt $s  e.g. ${nullExample[s]}")
        assertTrue("no expression statements found", exprKnown + exprNull > 0)
    }

    /**
     * Shape of an expression down to the first step whose type is unknown:
     * `TermExpr>Local[ClosureParam:(untyped)]{null}` or `TermExpr>Call[MethodDef:Str]>.Call[MethodDef:This]{null on Str}`.
     */
    private fun shape(expr: PsiElement): String {
        val parts = ArrayList<String>()
        var node: PsiElement = expr
        while (true) {
            if (node is FantomTermExpr) {
                parts += termShape(node)
                break
            }
            val kids = node.children
            when {
                kids.isEmpty() -> {
                    parts += kindOf(node)
                    break
                }
                kids.size == 1 -> node = kids[0]
                else -> {
                    val op = node.node.getChildren(null).firstOrNull { it.psi.children.isEmpty() && it.elementType != TokenType.WHITE_SPACE && it.psi !is FantomElement }?.text
                    parts += kindOf(node) + (op?.let { "($it)" } ?: "")
                    if (node is FantomCastExpr) {
                        parts += "type=" + node.type.text
                        break
                    }
                    node = kids[0]
                }
            }
        }
        return parts.joinToString(">")
    }

    private fun termShape(term: FantomTermExpr): String {
        val base = term.termBase
        val sb = StringBuilder("TermExpr>").append(baseShape(base))
        if (base == null || FantomTypeInference.typeOf(base) == null) return sb.append("{null}").toString()
        val chains = term.termChainList
        for ((i, c) in chains.withIndex()) {
            val op = c.dotCall ?: c.indexExpr ?: c.callOp ?: c.itBlock ?: c.stmt ?: continue
            val before = FantomTypeInference.receiverType(op)
            val after = if (i + 1 < chains.size) {
                val nextOp = chains[i + 1].let { it.dotCall ?: it.indexExpr ?: it.callOp ?: it.itBlock ?: it.stmt }
                nextOp?.let { FantomTypeInference.receiverType(it) }
            } else {
                FantomTypeInference.typeOf(term)
            }
            sb.append(">").append(chainShape(c))
            if (after == null) return sb.append("{null on ${before?.presentableText ?: "?"}}").toString()
        }
        return sb.append("{known}").toString()
    }

    private fun baseShape(base: FantomTermBase?): String {
        base ?: return "?"
        base.literal?.let { l ->
            val kind = when {
                l.node.findChildByType(FantomTypes.IT) != null -> "it"
                l.node.findChildByType(FantomTypes.THIS) != null -> "this"
                l.node.findChildByType(FantomTypes.SUPER) != null -> "super"
                l.bool != null -> "bool"
                l.str != null -> "str"
                l.list != null -> "list"
                l.map != null -> "map"
                else -> l.node.firstChildNode?.elementType?.toString() ?: "?"
            }
            return "Literal>$kind"
        }
        base.typeBase?.let { tb ->
            return "TypeBase>" + when {
                tb.simple != null -> "Simple"
                tb.staticCall != null -> "StaticCall[" + targetSummary(tb.staticCall?.slotExpr?.field?.id ?: tb.staticCall?.slotExpr?.call?.id) + "]"
                tb.typeLiteral != null -> "TypeLiteral"
                tb.closure != null -> "Closure"
                tb.ctorBlock != null -> "CtorBlock"
                tb.dsl != null -> "Dsl"
                else -> "NamedSuper"
            }
        }
        val idExpr = base.idExpr ?: return "?"
        idExpr.local?.let { return "IdExpr>Local[" + targetSummary(it.id) + "]" }
        return "IdExpr>" + slotShape(idExpr.slotExpr)
    }

    private fun slotShape(slotExpr: FantomSlotExpr?): String {
        slotExpr?.field?.let { return "Field[" + targetSummary(it.id) + "]" }
        slotExpr?.call?.let { return "Call[" + targetSummary(it.id) + "]" }
        return "?"
    }

    private fun chainShape(c: FantomTermChain): String {
        c.dotCall?.let { return "." + slotShape(it.slotExpr) }
        c.indexExpr?.let { return "[i]" }
        c.callOp?.let { return "(call)" }
        c.itBlock?.let { return "{it}" }
        return when (val s = c.stmt) {
            is FantomSafeDotCallStm -> "?." + slotShape(s.slotExpr)
            is FantomDynCallStm -> "->DynCall"
            is FantomSafeDynCallStm -> "?->DynCall"
            else -> "?"
        }
    }

    /** `MethodDef:Str` for one target, `MethodDef x3` for several, `0 targets` for none. */
    private fun targetSummary(id: FantomId?): String {
        val targets = (id?.reference as? FantomReference)?.resolveAll().orEmpty()
        if (targets.isEmpty()) return "0 targets"
        val kinds = targets.map { kindOf(it) }.distinct()
        val single = targets.singleOrNull() ?: return kinds.joinToString("|") + " x${targets.size}"
        return kinds[0] + ":" + declaredTypeText(single)
    }

    private fun declaredTypeText(d: FantomNamedElement): String = when (d) {
        is FantomFieldDef -> d.type?.text ?: "(no type)"
        is FantomMethodDef -> d.type?.text ?: "(no type)"
        is FantomParam -> d.type?.text ?: "(no type)"
        is FantomLocalDef -> d.type?.text ?: "(:=)"
        is FantomClosureParam -> d.type?.text ?: "(untyped)"
        is FantomCatchDef -> d.type?.text ?: "(untyped)"
        is FantomCtorDef -> "ctor"
        else -> "?"
    }

    // ---- 3: spot checks ---------------------------------------------------

    private fun spotChecks() {
        val fileIndex = ProjectFileIndex.getInstance(project)

        // Local from a ctor call: `x := Foo(...)` has type Foo.
        assertEquals("StrBuf", typeOfLocal("JsonOutStream.fan", "buf := StrBuf()"))
        assertEquals("StrBuf", typeOfLocal("Macro.fan", "keyBuf := StrBuf()"))
        assertEquals("Str[]", typeOfLocal("CsvInStream.fan", "cells := Str[,]"))

        // `this.cur` -> exactly one field, the project copy.
        val cur = resolveOne("JsonInStream.fan", "if (this.cur == JsonToken.quote) return parseStr", "cur")
        assertTrue("this.cur target is ${kindOf(cur)}", cur is FantomFieldDef)
        assertEquals("JsonInStream.fan", cur.containingFile.name)
        assertFalse("this.cur resolved into the library copy", fileIndex.isInLibrarySource(cur.containingFile.virtualFile))

        // Str method on a Str-typed local / field / param -> one method in Str.fan.
        assertEquals("Str", typeOfLocal("AbstractMain.fan", "tok := toks[i]"))
        assertMethodIn("Str.fan", resolveOne("AbstractMain.fan", "if (tok.startsWith(\"-\"))", "startsWith"))
        assertMethodIn("Str.fan", resolveOne("CsvInStream.fan", "while (pos < line.size) cells.add(parseCell)", "size"))
        assertMethodIn("Str.fan", resolveOne("CsvOutStream.fan", "if (cell.isEmpty) return true", "isEmpty"))

        // List method on a typed list -> one method in List.fan.
        assertMethodIn("List.fan", resolveOne("CsvInStream.fan", "while (pos < line.size) cells.add(parseCell)", "add"))
        assertMethodIn("List.fan", resolveOne("AbstractMain.fan", "rows.each |row| { row[0] = row[0].padr(pad) }", "each"))
        assertMethodIn("List.fan", resolveOne("AbstractMain.fan", "services.each |Service s| { s.install }", "each"))

        // Map: `[Str:Obj?]` local, index receiver, and a Map method -> Map.fan.
        assertEquals("[Str:Obj?]", typeOfLocal("JsonInStream.fan", "pairs := Str:Obj?[:] { ordered = true }"))
        assertMethodIn("Map.fan", resolveOne("JsonOutStream.fan", "map.each |val, key|", "each"))
        val index = elementsOnLine("JsonInStream.fan", "obj[key] = val", FantomIndexExpr::class.java).firstOrNull()
            ?: error("no index expression on `obj[key] = val`")
        val indexReceiver = FantomTypeInference.receiverType(index)
        println("$tag spot obj[key] receiver type = ${indexReceiver?.presentableText}")
        assertEquals("[Str:Obj?]", indexReceiver?.presentableText)
        assertEquals("Map", indexReceiver?.slotOwnerName)
        val mapGet = FantomTypeInference.typeDefinitions("Map", project).flatMap { FantomScope.ownSlots(it) }.filter { it.name == "get" }
        assertEquals("Map.get slots: $mapGet", 1, mapGet.size)
        assertEquals("Map.fan", mapGet[0].containingFile.name)
    }

    private fun assertMethodIn(fileName: String, target: FantomNamedElement) {
        assertTrue("target is ${kindOf(target)}", target is FantomMethodDef)
        assertEquals(fileName, target.containingFile.name)
    }

    private fun typeOfLocal(fileName: String, lineText: String): String? {
        val def = elementsOnLine(fileName, lineText, FantomLocalDef::class.java).firstOrNull()
            ?: error("no local def on `$lineText` in $fileName")
        val t = FantomTypeInference.typeOfDeclaration(def)
        println("$tag spot $fileName `$lineText` -> ${t?.presentableText}")
        return t?.presentableText
    }

    private fun <T : PsiElement> elementsOnLine(fileName: String, lineText: String, cls: Class<T>): List<T> {
        val psi = files[fileName] ?: error("$fileName not loaded")
        val doc = psi.viewProvider.document ?: error("no document for $fileName")
        val lines = (0 until doc.lineCount).filter {
            doc.text.substring(doc.getLineStartOffset(it), doc.getLineEndOffset(it)).trim() == lineText
        }
        assertTrue("$fileName has no line equal to '$lineText'", lines.isNotEmpty())
        val start = doc.getLineStartOffset(lines[0])
        val end = doc.getLineEndOffset(lines[0])
        return PsiTreeUtil.findChildrenOfType(psi, cls).filter { it.textOffset in start until end }.sortedBy { it.textOffset }
    }

    private fun resolveOne(fileName: String, lineText: String, idText: String): FantomNamedElement {
        val ids = elementsOnLine(fileName, lineText, FantomId::class.java).filter { it.text == idText && it.reference != null }
        assertTrue("$fileName: no id '$idText' with a reference on `$lineText`", ids.isNotEmpty())
        val ref = ids[0].reference as FantomReference
        val targets = ref.resolveAll()
        println("$tag spot $fileName '$idText' ${ref.kind} -> ${targets.map { "${kindOf(it)}@${it.containingFile.name}:${lineOf(it)}" }}")
        assertEquals("$fileName '$idText' (${ref.kind}) targets: ${targets.map { "${kindOf(it)}@${it.containingFile.name}" }}", 1, targets.size)
        return targets[0]
    }

    // ---- helpers ----------------------------------------------------------

    private fun kindOf(e: PsiElement): String = e.javaClass.simpleName.removeSuffix("Impl").removePrefix("Fantom")

    private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim().take(70)

    private fun lineOf(e: PsiElement): Int =
        (e.containingFile.viewProvider.document?.getLineNumber(e.textOffset) ?: -1) + 1

    private fun top(counts: Map<String, Int>, n: Int): List<Pair<String, Int>> =
        counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(n).map { it.key to it.value }
}
