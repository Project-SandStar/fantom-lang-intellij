package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.axon.AxonParserDefinition
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase

/**
 * Expected-tree tests for the Axon parser. Each test parses
 * `src/test/testData/axon/parser/<Name>.axon` and compares the PSI tree with
 * `<Name>.txt`. Every file but `Recovery.axon` must parse with no error.
 */
class AxonParserTest : ParsingTestCase("axon/parser", "axon", AxonParserDefinition()) {

    override fun getTestDataPath(): String = "src/test/testData"
    override fun skipSpaces(): Boolean = true
    override fun includeRanges(): Boolean = false

    private fun clean() = doTest(true, true)

    fun testLiterals() = clean()
    fun testLists() = clean()
    fun testDicts() = clean()
    fun testRanges() = clean()
    fun testDoBlock() = clean()
    fun testIfElse() = clean()
    fun testTryCatch() = clean()
    fun testThrowReturn() = clean()
    fun testLambdas() = clean()
    fun testDefcomp() = clean()
    fun testFunctionDefs() = clean()
    fun testCalls() = clean()
    fun testIndexTrap() = clean()
    fun testOperators() = clean()
    fun testComments() = clean()

    /**
     * One error must not break the rest of the file: the statements after
     * each bad line still parse to their own definitions.
     */
    fun testRecovery() {
        doTest(true, false)
        val errors = PsiTreeUtil.collectElementsOfType(myFile, PsiErrorElement::class.java)
        assertTrue("the fixture must have errors", errors.isNotEmpty())
        val text = toParseTreeText(myFile, true, false)
        for (name in listOf("b", "y", "g")) {
            assertTrue(
                "definition '$name' must survive the errors before it",
                Regex("""AxonDefExprImpl\(DEF_EXPR\)\s+PsiElement\(IDENTIFIER\)\('$name'\)""").containsMatchIn(text),
            )
        }
    }
}
