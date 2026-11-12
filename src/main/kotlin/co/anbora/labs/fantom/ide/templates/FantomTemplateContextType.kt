package co.anbora.labs.fantom.ide.templates

import co.anbora.labs.fantom.lang.FantomLanguage
import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType

/**
 * Live template context for Fantom files. One context is enough: every template
 * in `liveTemplates/Fantom.xml` applies anywhere in a `.fan` file.
 * The context id `FANTOM` comes from the `liveTemplateContext` entry in plugin.xml.
 */
class FantomTemplateContextType : TemplateContextType(FantomLanguage.LANGUAGE_NAME) {

    override fun isInContext(templateActionContext: TemplateActionContext): Boolean =
        templateActionContext.file.language.isKindOf(FantomLanguage)
}
