package co.anbora.labs.fantom.ide.completion

/**
 * The one place that lists which keywords completion offers where. Each group is
 * tied to a position; no group is offered after `.`, `?.` or `->`.
 */
object FantomKeywords {

    /** Top of a file: `using`, the four type kinds, and the flags a type header takes. */
    val FILE = listOf(
        "using", "class", "mixin", "enum", "facet",
        "abstract", "const", "final", "internal", "native", "public",
    )

    /** Inside a type body, before or between slot flags. */
    val TYPE_BODY = listOf(
        "abstract", "const", "final", "internal", "native", "new", "once", "override",
        "private", "protected", "public", "readonly", "static", "virtual",
    )

    /** Start of a statement. */
    val STATEMENT = listOf(
        "break", "case", "catch", "continue", "default", "else", "finally", "for", "if",
        "return", "switch", "throw", "try", "while",
    )

    /** Values that are keywords; legal wherever an expression is. */
    val VALUES = listOf("this", "super", "it", "true", "false", "null")
}
