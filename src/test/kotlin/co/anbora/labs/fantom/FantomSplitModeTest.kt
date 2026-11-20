package co.anbora.labs.fantom

import junit.framework.TestCase
import java.io.File

/**
 * Remote development (JetBrains Gateway, split mode): the plugin loads on the backend only,
 * and the frontend draws its UI. The language core (`lang/`: lexer, parser, PSI, index, the
 * Axon project copy) must stay free of Swing and dialog code, so it runs on a headless
 * backend and can later move to a shared module unchanged.
 *
 * What only a real Gateway session can check (status bar widgets, dialogs, file choosers,
 * the password store of the remote host) is listed in docs/features.md.
 */
class FantomSplitModeTest : TestCase() {

    private val langRoot = File("src/main/kotlin/co/anbora/labs/fantom/lang")

    /** Imports a backend-safe file may not have. `javax.swing.Icon` is data, not UI, and is allowed. */
    private val uiImport = Regex(
        """^import\s+(java\.awt\.|javax\.swing\.(?!Icon$)|com\.intellij\.ui\.|com\.intellij\.openapi\.ui\.|""" +
            """com\.intellij\.openapi\.fileChooser\.|com\.intellij\.openapi\.options\.ShowSettingsUtil|""" +
            """com\.intellij\.openapi\.wm\.)\S*"""
    )

    fun testLanguageCoreHasNoUiImports() {
        assertTrue("run from the project folder: ${langRoot.absolutePath}", langRoot.isDirectory)
        val found = langRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    val l = line.trim()
                    if (uiImport.containsMatchIn(l)) "${f.relativeTo(langRoot)}:${i + 1}: $l" else null
                }
            }
            .toList()
        assertTrue("UI imports in lang/ (move them to ide/):\n" + found.joinToString("\n"), found.isEmpty())
    }

    fun testGuardSeesUiImports() {
        assertTrue(uiImport.containsMatchIn("import javax.swing.JPanel"))
        assertTrue(uiImport.containsMatchIn("import com.intellij.openapi.ui.DialogWrapper"))
        assertTrue(uiImport.containsMatchIn("import java.awt.Component"))
        assertFalse(uiImport.containsMatchIn("import javax.swing.Icon"))
        assertFalse(uiImport.containsMatchIn("import com.intellij.notification.NotificationType"))
    }

    fun testSplitModeRunConfigurationsAreShared() {
        // "Run IDE (Split Mode)" starts a backend and a JetBrains Client on this machine.
        for (name in listOf("runIdeSplitMode", "runIdeBackend", "runIdeFrontend")) {
            assertTrue("$name.run.xml", File(".run/$name.run.xml").isFile)
        }
    }
}
