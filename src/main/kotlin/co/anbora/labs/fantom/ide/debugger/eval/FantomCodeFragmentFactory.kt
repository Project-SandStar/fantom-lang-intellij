package co.anbora.labs.fantom.ide.debugger.eval

import co.anbora.labs.fantom.lang.FantomFileType
import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.evaluation.CodeFragmentFactory
import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.engine.evaluation.EvaluateExceptionUtil
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.engine.evaluation.TextWithImports
import com.intellij.debugger.engine.evaluation.expression.EvaluatorBuilder
import com.intellij.debugger.engine.evaluation.expression.ExpressionEvaluator
import com.intellij.debugger.engine.evaluation.expression.Modifier
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiCodeFragment
import com.intellij.psi.PsiElement
import com.sun.jdi.ObjectReference
import com.sun.jdi.Value

/**
 * Evaluate Expression, Watches and breakpoint conditions in frames whose source is a `.fan`
 * file. The Java debugger picks this factory when the expression's file type, or the context
 * file's type, is [FantomFileType].
 */
class FantomCodeFragmentFactory : CodeFragmentFactory() {
    override fun createPsiCodeFragment(item: TextWithImports, context: PsiElement?, project: Project): PsiCodeFragment =
        FantomExpressionCodeFragment.create(project, "fragment.fan", item.text, context)

    override fun createPresentationPsiCodeFragment(item: TextWithImports, context: PsiElement?, project: Project): PsiCodeFragment =
        createPsiCodeFragment(item, context, project)

    override fun isContextAccepted(contextElement: PsiElement?): Boolean =
        contextElement?.containingFile?.fileType == FantomFileType

    override fun getFileType(): LanguageFileType = FantomFileType

    override fun getEvaluatorBuilder(): EvaluatorBuilder = FantomEvaluatorBuilder
}

/** Turns the fragment into an [FExpr] at build time, so evaluation touches no PSI. */
object FantomEvaluatorBuilder : EvaluatorBuilder {
    override fun build(codeFragment: PsiElement, position: SourcePosition?): ExpressionEvaluator {
        val ast = try {
            ApplicationManager.getApplication().runReadAction(Computable { FantomEvalAstBuilder.build(codeFragment.node) })
        } catch (e: FantomEvalException) {
            throw EvaluateExceptionUtil.createEvaluateException(e.message)
        }
        return FantomExpressionEvaluator(ast)
    }
}

class FantomExpressionEvaluator(val ast: FExpr) : ExpressionEvaluator {
    override fun getModifier(): Modifier? = null

    override fun evaluate(context: EvaluationContext): Value? {
        try {
            val ctx = context as? EvaluationContextImpl
                ?: throw EvaluateExceptionUtil.createEvaluateException("No debugger context")
            val runtime = JdiFantomRuntime(ctx)
            return unboxed(ctx, FantomInterpreter(runtime).eval(ast))
        } catch (e: EvaluateException) {
            throw e
        } catch (e: FantomEvalException) {
            throw EvaluateExceptionUtil.createEvaluateException(e.message)
        } catch (e: Throwable) {
            if (e is ControlFlowException) throw e
            throw EvaluateExceptionUtil.createEvaluateException(e)
        }
    }

    /** A boxed Int, Float or Bool result becomes a primitive: shown as a number, usable as a condition. */
    private fun unboxed(ctx: EvaluationContextImpl, v: Value?): Value? {
        if (v !is ObjectReference) return v
        val vm = ctx.virtualMachineProxy
        return when (val type = v.referenceType().name()) {
            "java.lang.Long", "java.lang.Double", "java.lang.Boolean" -> {
                when (val u = JdiFantomRuntime(ctx).unwrap(v)) {
                    is Long -> vm.mirrorOf(u)
                    is Double -> vm.mirrorOf(u)
                    is Boolean -> vm.mirrorOf(u)
                    else -> error("unexpected $type")
                }
            }
            else -> v
        }
    }
}
