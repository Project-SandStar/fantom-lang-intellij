package co.anbora.labs.fantom

import java.io.File

/**
 * Guards for tests that need a real install under `~` (Fantom, Haxall, SkySpark).
 *
 * Locally a missing install skips the test: [requireInstall] prints a line and
 * returns false, and the test returns early. When the environment variable
 * [ENV] is `true` (the GitLab CI job, which has the installs), a missing install
 * fails the test instead, so a broken CI image cannot pass by skipping.
 */
object TestInstalls {

    const val ENV = "FANTOM_REQUIRE_INSTALLS"

    /** True when missing installs must fail the test rather than skip it. */
    val required: Boolean get() = System.getenv(ENV).equals("true", ignoreCase = true)

    /**
     * True when [dir] (a folder or a file) exists. When it is null or missing:
     * false (skip) locally, or an [AssertionError] when [required].
     */
    fun requireInstall(dir: File?, what: String): Boolean =
        requireInstall(dir != null && dir.exists(), "$what (${dir?.path ?: "not found"})")

    /** As above for a condition the caller has checked: [present] false skips or fails. */
    fun requireInstall(present: Boolean, what: String): Boolean {
        if (present) return true
        val test = callerTest()
        if (required) throw AssertionError("$test needs $what, and $ENV=true is set")
        println("$test skipped: missing $what")
        return false
    }

    // Class.method of the test that called, for the message.
    private fun callerTest(): String =
        Throwable().stackTrace.firstOrNull { it.className != TestInstalls::class.java.name && it.methodName.startsWith("test") }
            ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}" }
            ?: Throwable().stackTrace.firstOrNull { it.className != TestInstalls::class.java.name }
                ?.className?.substringAfterLast('.') ?: "test"
}
