package co.anbora.labs.fantom

import co.anbora.labs.fantom.LexerTestSupport.checkInvariants
import co.anbora.labs.fantom.LexerTestSupport.visible
import co.anbora.labs.fantom.lang.props.FanPropsFileType
import co.anbora.labs.fantom.lang.props.FanPropsLexer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FanPropsTest : BasePlatformTestCase() {

    /** Start of Fantom 1.0.84 etc/sys/config.props. */
    private val config = """
        //
        // Config: sys
        //

        // Runtime - either "java" or "dotnet"
        runtime=java

        // Default depth of Err.trace used for stack tracing
        errTraceMaxDepth=25

        //timezone=New_York
        key with spaces = a value \
          on two lines
    """.trimIndent()

    fun testLexer() {
        checkInvariants(::FanPropsLexer, config)
        val toks = visible(FanPropsLexer(), config)
        assertEquals("FanProps:COMMENT '//'", toks.first())
        val at = toks.indexOf("FanProps:KEY 'runtime'")
        assertEquals(listOf("FanProps:KEY 'runtime'", "FanProps:EQ '='", "FanProps:VALUE 'java'"), toks.subList(at, at + 3))
        assertTrue(toks.contains("FanProps:COMMENT '//timezone=New_York'"))
        assertTrue(toks.contains("FanProps:KEY 'key with spaces'"))
        assertTrue(toks.contains("FanProps:VALUE 'a value \\\n  on two lines'"))
    }

    fun testFileTypeMapping() {
        val inEtc = myFixture.addFileToProject("etc/sys/config.props", "a=b").virtualFile
        val fanProps = myFixture.addFileToProject("lib/fan.props", "a=b").virtualFile
        val other = myFixture.addFileToProject("src/other.props", "a=b").virtualFile
        assertEquals(FanPropsFileType, inEtc.fileType)
        assertEquals(FanPropsFileType, fanProps.fileType)
        assertFalse(other.fileType == FanPropsFileType)
    }
}
