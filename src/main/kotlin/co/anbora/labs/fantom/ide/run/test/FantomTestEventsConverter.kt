package co.anbora.labs.fantom.ide.run.test

import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.openapi.util.Key
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageVisitor

/**
 * Feeds `fant` output through [FantOutputParser] into the SM test runner.
 * Stdout and stderr are read alike; system text (the command line) passes through.
 */
class FantomTestEventsConverter(
    testFrameworkName: String,
    consoleProperties: TestConsoleProperties,
    clock: () -> Long = System::currentTimeMillis,
) : OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties) {

    private val parser = FantOutputParser(clock)
    private var lastVisitor: ServiceMessageVisitor? = null

    override fun processServiceMessages(text: String, outputType: Key<*>, visitor: ServiceMessageVisitor): Boolean {
        lastVisitor = visitor
        if (outputType != ProcessOutputTypes.STDOUT && outputType != ProcessOutputTypes.STDERR) {
            return super.processServiceMessages(text, outputType, visitor)
        }
        if (text.trimStart().startsWith("##teamcity[")) return super.processServiceMessages(text, outputType, visitor)
        val parsed = parser.feed(text)
        for (message in parsed.messages) super.processServiceMessages(message, outputType, visitor)
        if (parsed.showLine && parsed.messages.isNotEmpty()) {
            fireOnUncapturedOutput(text, outputType)
        }
        return !parsed.showLine || parsed.messages.isNotEmpty()
    }

    override fun flushBufferOnProcessTermination(exitCode: Int) {
        super.flushBufferOnProcessTermination(exitCode)
        val visitor = lastVisitor ?: return
        for (message in parser.finish()) super.processServiceMessages(message, ProcessOutputTypes.STDOUT, visitor)
    }
}
