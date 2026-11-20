package co.anbora.labs.fantom.ide.actions

import com.intellij.openapi.ui.InputValidatorEx

/**
 * An Axon function name: a letter first, then letters, digits or `_`. The file name is the
 * function name, so SkySpark and the Axon server accept it. An extension the user types
 * (`calc.axon`) is allowed and dropped by the platform.
 */
object AxonFileNameValidator : InputValidatorEx {
    private val NAME = Regex("^[A-Za-z][A-Za-z0-9_]*$")

    fun nameOf(input: String): String = input.trim().removeSuffix(".axon").removeSuffix(".trio")

    override fun getErrorText(inputString: String): String? {
        val name = nameOf(inputString)
        return when {
            name.isEmpty() -> "Enter a name"
            !NAME.matches(name) -> "Use letters, digits and _, starting with a letter"
            else -> null
        }
    }

    override fun checkInput(inputString: String): Boolean = getErrorText(inputString) == null
    override fun canClose(inputString: String): Boolean = checkInput(inputString)
}
