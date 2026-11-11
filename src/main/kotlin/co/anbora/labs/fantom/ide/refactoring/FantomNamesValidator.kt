package co.anbora.labs.fantom.ide.refactoring

import com.intellij.lang.refactoring.NamesValidator
import com.intellij.openapi.project.Project

/**
 * Checks names for rename. A valid identifier is `[A-Za-z_][A-Za-z0-9_]*`
 * and not a reserved keyword. `enum`, `facet`, `get` and `set` are contextual
 * in Fantom, so they are plain identifiers here.
 */
class FantomNamesValidator : NamesValidator {

    override fun isKeyword(name: String, project: Project?): Boolean = name in KEYWORDS

    override fun isIdentifier(name: String, project: Project?): Boolean =
        IDENTIFIER_PATTERN.matches(name) && !isKeyword(name, project)

    companion object {
        private val IDENTIFIER_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")

        val KEYWORDS: Set<String> = setOf(
            "abstract", "as", "assert", "break", "case", "catch", "class", "const", "continue",
            "default", "do", "else", "false", "final", "finally", "for", "foreach", "if",
            "internal", "is", "isnot", "it", "mixin", "native", "new", "null", "once",
            "override", "private", "protected", "public", "readonly", "return", "static",
            "super", "switch", "this", "throw", "true", "try", "using", "virtual", "void",
            "volatile", "while",
        )
    }
}
