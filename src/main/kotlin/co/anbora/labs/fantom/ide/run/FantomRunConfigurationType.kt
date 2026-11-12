package co.anbora.labs.fantom.ide.run

import co.anbora.labs.fantom.ide.icons.FantomIcons
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import javax.swing.Icon

/**
 * Run | Edit Configurations | Fantom. Two factories: "Fantom" runs `fan <target>`,
 * "Fantom Test" runs `fant <pod>[::Class[.method]]`.
 */
class FantomRunConfigurationType : ConfigurationTypeBase(
    ID, "Fantom", "Run a Fantom pod method or script, or Fantom tests", FantomIcons.FILE
) {
    val mainFactory: ConfigurationFactory = MainFactory(this)
    val testFactory: ConfigurationFactory = TestFactory(this)

    init {
        addFactory(mainFactory)
        addFactory(testFactory)
    }

    private class MainFactory(type: ConfigurationType) : ConfigurationFactory(type) {
        override fun getId(): String = MAIN_FACTORY_ID
        override fun getName(): String = "Fantom"
        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            FantomRunConfiguration(project, this, "Fantom")
    }

    private class TestFactory(type: ConfigurationType) : ConfigurationFactory(type) {
        override fun getId(): String = TEST_FACTORY_ID
        override fun getName(): String = "Fantom Test"
        override fun getIcon(): Icon = AllIcons.RunConfigurations.TestState.Run
        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            FantomTestRunConfiguration(project, this, "Fantom Test")
    }

    companion object {
        const val ID = "FantomRunConfiguration"
        const val MAIN_FACTORY_ID = "Fantom"
        const val TEST_FACTORY_ID = "FantomTest"

        val instance: FantomRunConfigurationType
            get() = ConfigurationTypeUtil.findConfigurationType(FantomRunConfigurationType::class.java)
    }
}
