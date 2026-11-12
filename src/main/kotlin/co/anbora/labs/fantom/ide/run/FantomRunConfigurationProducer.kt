package co.anbora.labs.fantom.ide.run

import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomFile
import co.anbora.labs.fantom.lang.library.FantomPodResolver
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement

/**
 * Right-click or gutter on a `static Void main` (or anywhere in a class that has one)
 * gives a "Fantom" configuration. Target is `pod::Type.main` when the pod is known,
 * else the script path when the file stands alone (no `build.fan`, no `fan` folder).
 */
class FantomRunConfigurationProducer : LazyRunConfigurationProducer<RunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = FantomRunConfigurationType.instance.mainFactory

    override fun setupConfigurationFromContext(
        config: RunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        // Both factories share one type, so the platform may hand this producer the other kind.
        val configuration = config as? FantomRunConfiguration ?: return false
        val element = context.psiLocation ?: return false
        val cls = mainClassAt(element) ?: return false
        val target = targetFor(cls) ?: return false
        configuration.target = target
        configuration.name = "${cls.name}.main"
        sourceElement.set(FantomRunnable.mainOf(cls) ?: cls)
        return true
    }

    override fun isConfigurationFromContext(config: RunConfiguration, context: ConfigurationContext): Boolean {
        val configuration = config as? FantomRunConfiguration ?: return false
        val element = context.psiLocation ?: return false
        val cls = mainClassAt(element) ?: return false
        return configuration.target == targetFor(cls)
    }

    private fun mainClassAt(element: PsiElement): FantomClassDef? {
        if (element.containingFile !is FantomFile) return null
        val method = FantomRunnable.enclosingMethod(element)
        val cls = FantomRunnable.enclosingClass(element) ?: return null
        if (method != null && FantomRunnable.isTestMethod(method)) return null
        return cls.takeIf { FantomRunnable.mainOf(it) != null }
    }

    private fun targetFor(cls: FantomClassDef): String? {
        val file = cls.containingFile?.virtualFile ?: return null
        val name = cls.name ?: return null
        val pod = FantomPodResolver.podNameFor(file, cls.project)
        return if (pod != null) "$pod::$name.main" else file.path
    }
}
