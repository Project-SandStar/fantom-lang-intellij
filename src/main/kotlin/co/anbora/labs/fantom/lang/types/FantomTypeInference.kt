package co.anbora.labs.fantom.lang.types

import co.anbora.labs.fantom.lang.core.psi.*
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.AS
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.CHAR_LITERAL
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.COLON
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.DECIMAL_LITERAL
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.DURATION_LITERAL
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.FLOAT_LITERAL
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.INT_LITERAL
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.IS
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.ISNOT
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.IT
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.LBRACKET
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.NULL
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.QUEST
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.QUEST_COLON
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.RBRACKET
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.SUPER
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.THIS
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.URI_LITERAL
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.resolve.FantomReference
import co.anbora.labs.fantom.lang.resolve.FantomScope
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil

/**
 * Static type of an expression or a declaration, as far as syntax and the
 * declaration index allow. Returns null where the type is not known. Results are
 * cached per PSI element until the next PSI change.
 *
 * Rules, in the compiler's spirit but simplified:
 * literals have fixed types; `x` has the declared type of what it resolves to, or
 * the type of its `:=` initializer; `a.b` has the type of slot `b` on the type of
 * `a`; `Foo(...)` and `Foo { }` are `Foo`; `(T)x` and `x as T` are `T`; comparisons
 * and `is` are `Bool`; `a ?: b` and `a ? b : c` take the type of the first branch.
 */
object FantomTypeInference {

    /** Type of a declaration: declared type first, else the type of its initializer. */
    fun typeOfDeclaration(decl: FantomNamedElement): FanType? = cached(decl) {
        when (decl) {
            is FantomFieldDef -> fromPsiType(decl.type) ?: decl.expr?.let { typeOf(it) }
            is FantomLocalDef -> fromPsiType(decl.type) ?: decl.expr?.let { typeOf(it) }
            is FantomParam -> fromPsiType(decl.type) ?: decl.expr?.let { typeOf(it) }
            is FantomClosureParam -> fromPsiType(decl.type) ?: FantomItScope.typeOfClosureParam(decl)
            is FantomCatchDef -> fromPsiType(decl.type)
            is FantomMethodDef -> fromPsiType(decl.type)
            is FantomCtorDef -> FantomScope.enclosingType(decl)?.name?.let { FanType.Named(it) }
            is FantomEnumValDef -> FantomScope.enclosingType(decl)?.name?.let { FanType.Named(it) }
            else -> if (FantomScope.isTypeDef(decl)) decl.name?.let { FanType.Named(it) } else null
        }
    }

    /** Type of any expression-level PSI node, or null when unknown. */
    fun typeOf(e: PsiElement): FanType? = cached(e) { infer(e) }

    /**
     * Type of the receiver of a chain operator (`.x`, `?.x`, `->x`, `[i]`, `(...)`):
     * the term base plus every chain step before this one.
     */
    fun receiverType(chainOp: PsiElement): FanType? {
        val step = chainOp.parent as? FantomTermChain ?: return null
        val container = step.parent ?: return null
        val (base, chains) = when (container) {
            is FantomTermExpr -> (container.termBase?.let { typeOf(it) }) to container.termChainList
            is FantomGroupedExpr -> (container.expr?.let { typeOf(it) }) to container.termChainList
            else -> return null
        }
        var t = base ?: return null
        for (i in chains.indices) {
            if (chains[i] == step) return t
            t = applyChain(t, chains, i) ?: return null
        }
        return null
    }

    /** The type declarations named [name], project copies first. */
    fun typeDefinitions(name: String, project: Project): List<FantomNamedElement> {
        val all = FantomDeclarationIndex.findDeclarations(name, project, GlobalSearchScope.allScope(project)) { it.isType }
        if (all.size < 2) return all
        val index = ProjectFileIndex.getInstance(project)
        val inProject = all.filter { t ->
            val vf = t.containingFile?.virtualFile ?: return@filter false
            index.isInContent(vf) && !index.isInLibrary(vf)
        }
        return inProject.ifEmpty { all }
    }

    // ---- declared types ------------------------------------------------------------

    /**
     * Reads a written type: `Str`, `Str?`, `Str[]`, `[Str:Int]`, `Str:Int`, `|Int->Str|`,
     * `pod::Type`. Grammar: `type ::= nonMapType [":" type]` where `nonMapType` is a base
     * followed by `?` and `[]` suffix tokens that sit directly under the type node.
     */
    fun fromPsiType(type: FantomType?): FanType? {
        type ?: return null
        var t: FanType = baseOf(type.simpleType, type.mapType, type.funcType) ?: return null
        t = applySuffixTokens(type, t, skipPastBase = type.mapType)
        // `K:V` map shorthand: the value type is the `type` child after ':'.
        type.type?.let { value -> t = FanType.MapOf(t, fromPsiType(value) ?: FanType.OBJ) }
        return t
    }

    // `[` nonMapType `:` type `]`: the key is the base plus suffixes before the ':', the value after.
    private fun fromMapType(m: FantomMapType): FanType? {
        var key: FanType = baseOf(m.simpleType, m.mapType, m.funcType) ?: return null
        key = applySuffixTokens(m, key, skipPastBase = m.mapType, stopAtColon = true)
        val value = fromPsiType(m.type) ?: FanType.OBJ
        return FanType.MapOf(key, value)
    }

    private fun fromFuncType(f: FantomFuncType): FanType {
        val params = f.formals?.formalList?.mapNotNull { formal ->
            formal.formalFull?.type?.let { fromPsiType(it) }
        } ?: emptyList()
        return FanType.Func(params, fromPsiType(f.type))
    }

    private fun baseOf(simple: FantomSimpleType?, map: FantomMapType?, func: FantomFuncType?): FanType? = when {
        simple != null -> simple.idList.lastOrNull()?.text?.let { FanType.Named(it) }
        map != null -> fromMapType(map)
        func != null -> fromFuncType(func)
        else -> null
    }

    /**
     * `?` and `[` `]` suffixes are direct child tokens of the type node, applied left to
     * right. A map type `[K:V]` opens with its own `[`, so suffix reading starts after the
     * base node when [skipPastBase] is given. Inside a map type the key's suffixes end at `:`.
     */
    private fun applySuffixTokens(
        node: PsiElement,
        start: FanType,
        skipPastBase: PsiElement? = null,
        stopAtColon: Boolean = false,
    ): FanType {
        var t = start
        var child: PsiElement? = if (skipPastBase != null) skipPastBase.nextSibling else node.firstChild
        if (skipPastBase == null && node is FantomMapType) {
            // the key base follows the opening '['
            child = node.firstChild?.nextSibling
        }
        while (child != null) {
            val et = child.node.elementType
            if (stopAtColon && et == COLON) break
            when (et) {
                QUEST -> t = t.withNullable(true)
                LBRACKET -> if (child.nextSibling?.node?.elementType == RBRACKET) t = FanType.ListOf(t)
            }
            child = child.nextSibling
        }
        return t
    }

    // ---- expressions ---------------------------------------------------------------

    private fun infer(e: PsiElement): FanType? = when (e) {
        is FantomExpr -> e.assignExpr?.let { typeOf(it) }
        is FantomAssignExpr -> e.ifExpr?.let { typeOf(it) }
        is FantomIfExpr -> inferIf(e)
        is FantomIfExprBody -> e.condOrExpr?.let { typeOf(it) }
        is FantomCondOrExpr -> if (e.condAndExprList.size > 1) FanType.BOOL else e.condAndExprList.firstOrNull()?.let { typeOf(it) }
        is FantomCondAndExpr -> if (e.equalityExprList.size > 1) FanType.BOOL else e.equalityExprList.firstOrNull()?.let { typeOf(it) }
        is FantomEqualityExpr -> if (e.relationalExprList.size > 1) FanType.BOOL else e.relationalExprList.firstOrNull()?.let { typeOf(it) }
        is FantomRelationalExpr -> inferRelational(e)
        is FantomRangeExpr -> if (e.addExprList.size > 1) FanType.RANGE else e.addExprList.firstOrNull()?.let { typeOf(it) }
        is FantomAddExpr -> e.multExprList.firstOrNull()?.let { typeOf(it) }
        is FantomMultExpr -> e.parenExprList.firstOrNull()?.let { typeOf(it) }
        is FantomParenExpr -> (e.unaryExpr ?: e.castExpr ?: e.groupedExpr)?.let { typeOf(it) }
        is FantomCastExpr -> fromPsiType(e.type)
        is FantomGroupedExpr -> e.expr?.let { typeOf(it) }?.let { applyChains(it, e.termChainList) }
        is FantomUnaryExpr -> (e.prefixExpr ?: e.postfixExpr ?: e.termExpr)?.let { typeOf(it) }
        is FantomPrefixExpr -> if (e.firstChild?.text == "!") FanType.BOOL else e.parenExpr?.let { typeOf(it) }
        is FantomPostfixExpr -> e.termExpr?.let { typeOf(it) }
        is FantomTermExpr -> e.termBase?.let { typeOf(it) }?.let { applyChains(it, e.termChainList) }
        is FantomTermBase -> (e.literal ?: e.typeBase ?: e.idExpr)?.let { typeOf(it) }
        is FantomLiteral -> inferLiteral(e)
        is FantomTypeBase -> inferTypeBase(e)
        is FantomIdExpr -> (e.local ?: e.slotExpr)?.let { typeOf(it) }
        // A bare name (`put` in `put("a").put("b")`) parses as a local; a `This` slot means the enclosing type.
        is FantomLocal -> typeOfReference(e.id)?.let { resolveThis(it, FantomScope.enclosingType(e)?.name?.let { n -> FanType.Named(n) }) }
        is FantomSlotExpr -> (e.field ?: e.call)?.let { typeOf(it) }
        is FantomField -> typeOfReference(e.id)?.let { resolveThis(it, FantomScope.enclosingType(e)?.name?.let { n -> FanType.Named(n) }) }
        is FantomCall -> typeOfReference(e.id)?.let { resolveThis(it, FantomScope.enclosingType(e)?.name?.let { n -> FanType.Named(n) }) }
        is FantomList -> fromPsiType(e.type)?.let { FanType.ListOf(it) }
            ?: FanType.ListOf(e.listItems?.exprList?.firstOrNull()?.let { typeOf(it) } ?: FanType.OBJ)
        is FantomMap -> fromPsiType(e.type) ?: run {
            // `["a": 1]`: key and value types from the first pair.
            val pair = e.mapItems?.mapPairList?.firstOrNull()?.exprList
            FanType.MapOf(
                pair?.getOrNull(0)?.let { typeOf(it) } ?: FanType.OBJ,
                pair?.getOrNull(1)?.let { typeOf(it) } ?: FanType.OBJ,
            )
        }
        is FantomNamedElement -> typeOfDeclaration(e)
        else -> null
    }

    private fun inferIf(e: FantomIfExpr): FanType? {
        val cond = e.condOrExpr?.let { typeOf(it) }
        val bodies = e.ifExprBodyList
        if (bodies.isEmpty()) return cond
        // `a ?: b` keeps a's type without the null; `c ? a : b` takes a's type.
        return if (e.node.findChildByType(QUEST_COLON) != null) cond?.withNullable(false) ?: typeOf(bodies[0])
        else typeOf(bodies[0])
    }

    private fun inferRelational(e: FantomRelationalExpr): FanType? {
        val type = e.type
        if (type != null) {
            return when {
                e.node.findChildByType(IS) != null || e.node.findChildByType(ISNOT) != null -> FanType.BOOL
                e.node.findChildByType(AS) != null -> fromPsiType(type)?.withNullable(true)
                else -> null
            }
        }
        val ranges = e.rangeExprList
        if (ranges.size > 1) return if (e.text.contains("<=>")) FanType.INT else FanType.BOOL
        return ranges.firstOrNull()?.let { typeOf(it) }
    }

    private fun inferLiteral(e: FantomLiteral): FanType? {
        e.bool?.let { return FanType.BOOL }
        e.str?.let { s -> return if (s.node.firstChildNode?.elementType == CHAR_LITERAL) FanType.INT else FanType.STR }
        e.list?.let { return typeOf(it) }
        e.map?.let { return typeOf(it) }
        return when (e.node.firstChildNode?.elementType) {
            INT_LITERAL -> FanType.INT
            FLOAT_LITERAL -> FanType.FLOAT
            DECIMAL_LITERAL -> FanType.DECIMAL
            DURATION_LITERAL -> FanType.DURATION
            URI_LITERAL -> FanType.URI
            NULL -> FanType.NULL
            THIS -> FantomScope.enclosingType(e)?.name?.let { FanType.Named(it) }
            SUPER -> FantomScope.enclosingType(e)?.let { FantomScope.superTypeNames(it).firstOrNull() }?.let { FanType.Named(it) }
            IT -> FantomItScope.typeOfIt(e)
            else -> null
        }
    }

    private fun inferTypeBase(e: FantomTypeBase): FanType? {
        e.typeLiteral?.let { return FanType.TYPE }
        e.namedSuper?.let { return fromPsiType(it.type) }
        e.staticCall?.let { sc -> return slotType(sc.slotExpr, fromPsiType(sc.type)) }
        e.dsl?.let { return fromPsiType(it.type) }
        e.simple?.let { return fromPsiType(it.type)?.withNullable(false) }
        e.ctorBlock?.let { return fromPsiType(it.type)?.withNullable(false) }
        e.closure?.let { return typeOfClosure(it) }
        return null
    }

    /**
     * A closure is a `Func` of its declared param types (an untyped param takes the type
     * the called method's func param gives it, else `Obj`) and its declared return type.
     * Without `->T` in the sig, the return type is that of a `return x` in the body, else
     * of the last expression statement; an empty body returns `Void`.
     */
    fun typeOfClosure(c: FantomClosure): FanType.Func = cached(c) {
        val sig = c.closureSig
        val params = sig?.closureParams?.closureParamList?.map { typeOfDeclaration(it) ?: FanType.OBJ } ?: emptyList()
        FanType.Func(params, sig?.type?.let { fromPsiType(it) } ?: closureBodyReturn(c))
    } as? FanType.Func ?: FanType.Func(emptyList(), null)

    private fun closureBodyReturn(c: FantomClosure): FanType? {
        val block = c.statementBlock ?: return null
        val ret = PsiTreeUtil.findChildrenOfType(block, FantomReturnStm::class.java)
            .firstOrNull { PsiTreeUtil.getParentOfType(it, FantomClosure::class.java) == c }
        if (ret != null) return ret.expr?.let { typeOf(it) } ?: FanType.VOID
        val last = block.stmts?.stmtList?.lastOrNull() ?: return FanType.VOID
        return last.exprStmt?.expr?.let { typeOf(it) }
    }

    /** Type of what an identifier use resolves to; several targets must agree or the type is unknown. */
    private fun typeOfReference(id: FantomId?): FanType? {
        val ref = id?.reference as? FantomReference ?: return null
        val targets = ref.resolveAll()
        val types = targets.mapNotNull { typeOfDeclaration(it) }.distinct()
        return types.singleOrNull()
    }

    private fun applyChains(start: FanType, chains: List<FantomTermChain>): FanType? {
        var t: FanType = start
        for (i in chains.indices) t = applyChain(t, chains, i) ?: return null
        return t
    }

    /**
     * Type after chain step [i]. `recv.m |x| { }` and `recv.m(a) |x| { }` parse as the
     * step `.m` followed by a call step that holds the closure, so the slot step looks
     * one step ahead for the closure whose return type binds `R`.
     */
    private fun applyChain(receiver: FanType, chains: List<FantomTermChain>, i: Int): FanType? {
        val chain = chains[i]
        val next = chains.getOrNull(i + 1)
        chain.dotCall?.let { return slotType(it.slotExpr, receiver, closureOf(next), next?.callOp?.args?.exprList) }
        chain.indexExpr?.let { ix ->
            // `x[a..b]` is getRange and keeps the container type; `x[i]` is get.
            val byRange = ix.expr?.let { typeOf(it) } == FanType.RANGE
            return when (receiver) {
                is FanType.ListOf -> if (byRange) receiver else receiver.element
                is FanType.MapOf -> receiver.value.withNullable(true)
                is FanType.Named -> if (receiver.name == "Str") (if (byRange) FanType.STR else FanType.INT) else null
                else -> null
            }
        }
        chain.callOp?.let { return if (receiver is FanType.Func) receiver.returns else receiver }
        chain.itBlock?.let { return receiver }
        when (val stm = chain.stmt) {
            is FantomSafeDotCallStm -> return slotType(stm.slotExpr, receiver, closureOf(next), next?.callOp?.args?.exprList)?.withNullable(true)
            is FantomDynCallStm, is FantomSafeDynCallStm -> return null // dynamic call: unknown
        }
        return null
    }

    /**
     * Type of slot `recv.x`. A slot declared as `This` returns the receiver's own type
     * (fluent APIs such as `StrBuf.add`, `OutStream.print`), so chains keep their type.
     */
    private fun slotType(
        slotExpr: FantomSlotExpr?,
        receiver: FanType?,
        closure: FantomClosure? = null,
        nextArgs: List<FantomExpr>? = null,
    ): FanType? {
        val call = slotExpr?.call
        val ref = (slotExpr?.field?.id ?: call?.id)?.reference as? FantomReference ?: return null
        val closureArg = closure ?: call?.let { closureArgument(it) }
        val args = call?.args?.exprList ?: nextArgs
        val types = ref.resolveAll().mapNotNull { target ->
            val declared = typeOfDeclaration(target) ?: return@mapNotNull null
            val owner = FantomDeclarationIndex.enclosingTypeName(target)
            val generic = owner in FantomGenerics.GENERIC_OWNERS
            val closureReturn = closureArg?.let { typeOfClosure(it).returns }
            if (generic && FantomGenerics.TABLE.containsKey(owner to target.name)) {
                val first = args?.firstOrNull()?.takeIf { closureExpr(it) == null }
                FantomGenerics.bindResult(
                    owner, target.name, receiver, closureReturn,
                    firstArg = first?.let { typeOf(it) },
                    typeArg = first?.let { typeLiteralArg(it) },
                )?.let { return@mapNotNull it }
            }
            val extra = if (closureReturn != null) mapOf("R" to closureReturn) else emptyMap()
            FantomGenerics.substitute(declared, receiver, extra, generic)
        }.distinct()
        return types.singleOrNull()
    }

    /** `T` when [e] is just the type literal `T#`. */
    private fun typeLiteralArg(e: FantomExpr): FanType? {
        val lit = PsiTreeUtil.findChildOfType(e, FantomTypeLiteral::class.java) ?: return null
        if (lit.textRange != e.textRange || lit.id != null) return null
        return fromPsiType(lit.type)
    }

    /** The closure handed to [call]: trailing (`f |x| { }`, `f(a) |x| { }`) or as the last argument. */
    private fun closureArgument(call: FantomCall): FantomClosure? =
        call.closure ?: call.args?.exprList?.lastOrNull()?.let { closureExpr(it) }

    /** The closure a call step holds: trailing, or as the last argument. */
    private fun closureOf(step: FantomTermChain?): FantomClosure? {
        val op = step?.callOp ?: return null
        return op.closure ?: op.args?.exprList?.lastOrNull()?.let { closureExpr(it) }
    }

    private fun closureExpr(e: FantomExpr): FantomClosure? =
        PsiTreeUtil.findChildOfType(e, FantomClosure::class.java)?.takeIf { it.textRange == e.textRange }

    /** `This` stands for the receiver type; nullability of the declared type is kept. */
    fun resolveThis(declared: FanType, receiver: FanType?): FanType =
        if (declared is FanType.Named && declared.name == "This" && receiver != null) receiver.withNullable(declared.nullable)
        else declared

    private fun <T : PsiElement> cached(e: T, compute: () -> FanType?): FanType? =
        CachedValuesManager.getCachedValue(e) {
            val value = RecursionManager.doPreventingRecursion(e, false) { compute() }
            CachedValueProvider.Result.create(value, PsiModificationTracker.MODIFICATION_COUNT)
        }
}
