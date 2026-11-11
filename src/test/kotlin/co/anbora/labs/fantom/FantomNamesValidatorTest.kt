package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.refactoring.FantomNamesValidator
import junit.framework.TestCase

class FantomNamesValidatorTest : TestCase() {

    private val validator = FantomNamesValidator()

    fun testKeyword() {
        assertTrue(validator.isKeyword("class", null))
        assertFalse(validator.isKeyword("enum", null))
    }

    fun testIdentifier() {
        assertTrue(validator.isIdentifier("foo_1", null))
        assertTrue(validator.isIdentifier("enum", null))
    }

    fun testNotIdentifier() {
        assertFalse(validator.isIdentifier("1foo", null))
        assertFalse(validator.isIdentifier("class", null))
        assertFalse(validator.isIdentifier("", null))
    }
}
