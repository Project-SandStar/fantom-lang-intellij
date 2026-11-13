package co.anbora.labs.fantom.ide.run.test


/**
 * Turns `fant` output, one line at a time, into TeamCity service messages.
 *
 * `fant` prints no machine format, so this reads its human lines. Two printers exist:
 *
 * Fantom 1.0.80+ (`util::TestRunner`), all on stdout:
 * ```
 * -- Run: footest::FooTest.testA
 *    Pass: footest::FooTest.testA [1]
 * -- Run: footest::FooTest.testB
 *
 * TEST FAILED
 * sys::TestErr: Test failed: 1 [sys::Int] != 2 [sys::Int]
 *   fan.sys.Test.err (Test.java:239)
 *   footest::FooTest.testB (FooTest.fan:1)
 *
 * Time: 20ms
 *
 * Failed:
 *   footest::FooTest.testB
 *
 * ***
 * *** 1 FAILURES [1 types, 2 methods, 1 verifies]
 * ***
 * ```
 * Fantom 1.0.79 and older (`fanx.tools.Fant`): `-- Run:  footest::FooTest.testA...` (two
 * spaces, three dots), `ERROR: Cannot make test <type>` when the class cannot be made, and the
 * error trace on stderr (the run merges stderr into stdout so it stays in order).
 *
 * Test results come from the `Run`, `Pass` and `TEST FAILED` lines only. The summary line is
 * passed through as text: its counts differ between versions (`tests` against `types`).
 *
 * `fant` prints no time per test, so the duration is the [clock] time between the `Run` line
 * and the `Pass` or `TEST FAILED` line.
 */
class FantOutputParser(private val clock: () -> Long = System::currentTimeMillis) {

    private var attached = false
    private var suite: String? = null
    private var test: Running? = null
    private var done = false
    private var pendingBlank = 0

    private class Running(val qname: String, val name: String, val start: Long) {
        var failedAt: Long = -1
        var message: String? = null
        val stack = StringBuilder()
        val failed: Boolean get() = failedAt >= 0
    }

    /** What one line turned into: service messages, and whether the line itself is still shown as text. */
    data class Parsed(val messages: List<String>, val showLine: Boolean)

    /** One output line, with or without its line end. */
    fun feed(rawLine: String): Parsed {
        val messages = ArrayList<String>()
        val show = feed(rawLine.trimEnd('\n', '\r'), messages)
        return Parsed(messages, show)
    }

    /** Adds the messages for [line] to [out]; true when the line is plain text to show as is. */
    private fun feed(line: String, out: MutableList<String>): Boolean {
        if (!attached) {
            attached = true
            out += Msg("enteredTheMatrix").toString()
        }

        run.matchEntire(line)?.let { m ->
            closeTest(out)
            startTest(m.groupValues[1], out)
            return false
        }
        val current = test
        if (current != null) {
            pass.matchEntire(line)?.let { m ->
                if (m.groupValues[1] == current.qname && !current.failed) {
                    pendingBlank = 0
                    closeTest(out)
                    return false
                }
            }
            if (line == "TEST FAILED" && !current.failed) {
                pendingBlank = 0
                current.failedAt = clock()
                return false
            }
            if (line.startsWith(CANNOT_MAKE) && !current.failed) {
                pendingBlank = 0
                current.failedAt = clock()
                current.message = line
                return false
            }
            if (time.matches(line)) {
                closeTest(out)
                closeSuite(out)
                done = true
                return true
            }
            if (current.failed) {
                if (current.message == null) {
                    if (line.isNotBlank()) current.message = line
                } else {
                    current.stack.append(line).append('\n')
                }
                return false
            }
            if (line.isBlank()) {
                pendingBlank++
                return false
            }
            flushBlanks(current, out)
            out += Msg("testStdOut").name(current.name).addAttribute("out", line + "\n").toString()
            return false
        }
        if (!done && time.matches(line)) {
            closeSuite(out)
            done = true
        }
        return true
    }

    /** Closes whatever is still open when the process ends. */
    fun finish(): List<String> {
        val out = ArrayList<String>()
        val current = test
        if (current != null && !current.failed) {
            current.failedAt = clock()
            current.message = "Test did not finish: the fant process ended"
        }
        closeTest(out)
        closeSuite(out)
        return out
    }

    private fun startTest(qname: String, out: MutableList<String>) {
        val sep = qname.lastIndexOf('.')
        val typeQname = if (sep > 0) qname.substring(0, sep) else qname
        val method = if (sep > 0) qname.substring(sep + 1) else qname
        if (typeQname != suite) {
            closeSuite(out)
            suite = typeQname
            out += Msg("testSuiteStarted").name(typeQname.substringAfter("::"))
                .addAttribute("locationHint", FantomTestLocator.url(typeQname)).toString()
        }
        test = Running(qname, method, clock())
        pendingBlank = 0
        out += Msg("testStarted").name(method)
            .addAttribute("locationHint", FantomTestLocator.url(qname)).toString()
    }

    private fun flushBlanks(current: Running, out: MutableList<String>) {
        if (pendingBlank == 0) return
        out += Msg("testStdOut").name(current.name).addAttribute("out", "\n".repeat(pendingBlank)).toString()
        pendingBlank = 0
    }

    private fun closeTest(out: MutableList<String>) {
        val current = test ?: return
        test = null
        pendingBlank = 0
        val end = if (current.failed) current.failedAt else clock()
        if (current.failed) {
            out += Msg("testFailed").name(current.name)
                .addAttribute("message", current.message ?: "Test failed")
                .addAttribute("details", current.stack.toString().trimEnd())
                .toString()
        }
        out += Msg("testFinished").name(current.name)
            .addAttribute("duration", (end - current.start).coerceAtLeast(0).toString())
            .toString()
    }

    private fun closeSuite(out: MutableList<String>) {
        val s = suite ?: return
        suite = null
        out += Msg("testSuiteFinished").name(s.substringAfter("::")).toString()
    }

    /** A `##teamcity[...]` line with escaped attribute values. */
    private class Msg(private val type: String) {
        private val attrs = LinkedHashMap<String, String>()
        fun name(value: String) = addAttribute("name", value)
        fun addAttribute(key: String, value: String): Msg { attrs[key] = value; return this }
        override fun toString(): String = buildString {
            append("##teamcity[").append(type)
            for ((k, v) in attrs) append(' ').append(k).append("='").append(escape(v)).append('\'')
            append(']')
        }
    }

    companion object {
        /** TeamCity escaping: `|`, `'`, `[`, `]` and line ends get a `|` prefix. */
        fun escape(value: String): String = buildString {
            for (c in value) when (c) {
                '|' -> append("||"); '\'' -> append("|'"); '[' -> append("|["); ']' -> append("|]")
                '\n' -> append("|n"); '\r' -> append("|r")
                '\u0085' -> append("|x"); '\u2028' -> append("|l"); '\u2029' -> append("|p")
                else -> append(c)
            }
        }

        /** `-- Run: pod::Type.method` (1.0.80+) or `-- Run:  pod::Type.method...` (older). */
        val run = Regex("""^-- Run:\s+(\S+?::\S+?)(?:\.\.\.)?\s*$""")
        /** `   Pass: pod::Type.method [verifies]`. */
        val pass = Regex("""^\s+Pass: (\S+) \[\d+]\s*$""")
        /** `Time: 20ms`: the summary starts. */
        val time = Regex("""^Time: .*$""")
        const val CANNOT_MAKE = "ERROR: Cannot make test"
    }
}
