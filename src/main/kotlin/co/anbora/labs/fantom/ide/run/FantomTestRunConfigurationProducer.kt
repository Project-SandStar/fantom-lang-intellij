package co.anbora.labs.fantom.ide.run

import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomFile
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.library.FantomPodResolver
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement

/**
 * Right-click or gutter on a `Test` subclass gives `fant pod::Class`; on one of its
 * `testXxx` methods gives `fant pod::Class.testXxx`.
 */
class FantomTestRunConfigurationProducer : LazyRunConfigurationProducer<RunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = FantomRunConfigurationType.instance.testFactory

    override fun setupConfigurationFromContext(
        config: RunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        // Both factories share one type, so the platform may hand this producer the other kind.
        val configuration = config as? FantomTestRunConfiguration ?: return false
        val element = context.psiLocation ?: return false
        val spec = specAt(element) ?: return false
        configuration.pod = spec.pod
        configuration.testClass = spec.cls.name ?: return false
        configuration.testMethod = spec.method?.name ?: ""
        configuration.name = configuration.suggestedName() ?: return false
        sourceElement.set(spec.method ?: spec.cls)
        return true
    }

    override fun isConfigurationFromContext(config: RunConfiguration, context: ConfigurationContext): Boolean {
        val configuration = config as? FantomTestRunConfiguration ?: return false
        val element = context.psiLocation ?: return false
        val spec = specAt(element) ?: return false
        return configuration.pod == spec.pod &&
            configuration.testClass == spec.cls.name &&
            configuration.testMethod == (spec.method?.name ?: "")
    }

    private class Spec(val pod: String, val cls: FantomClassDef, val method: FantomMethodDef?)

    private fun specAt(element: PsiElement): Spec? {
        if (element.containingFile !is FantomFile) return null
        val cls = FantomRunnable.enclosingClass(element) ?: return null
        if (!FantomRunnable.isTestClass(cls)) return null
        val file = cls.containingFile?.virtualFile ?: return null
        val pod = FantomPodResolver.podNameFor(file, cls.project) ?: return null
        val method = FantomRunnable.enclosingMethod(element)?.takeIf { FantomRunnable.isTestMethod(it) }
        return Spec(pod, cls, method)
    }
}
