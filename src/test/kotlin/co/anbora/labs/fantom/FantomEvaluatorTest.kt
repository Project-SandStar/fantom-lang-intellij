package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.debugger.eval.*
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The Fantom debugger evaluator without a VM: the fragment is parsed with the plugin's parser,
 * turned into an [FExpr] and run on a fake runtime of plain Kotlin values.
 */
class FantomEvaluatorTest : BasePlatformTestCase() {

    /** A Fantom object of the fake VM. */
    private data class Obj(val type: String, val fields: Map<String, Any?>)

    private val locals = mapOf<String, Any?>(
        "x" to 3L, "y" to 2.5, "s" to "abc", "b" to true, "n" to null,
        "list" to mutableListOf<Any?>(1L, 2L, 3L), "map" to mapOf("a" to 1L),
        "f" to Obj("Foo", mapOf("name" to "fooName")),
    )
    private val self = Obj("Foo", mapOf("count" to 7L))

    private val fake = object : FantomEvalRuntime<Any> {
        override fun mirror(value: Any?) = value
        override fun unwrap(value: Any?) = when (value) {
            null, is Long, is Double, is String, is Boolean -> value
            else -> FantomEvalRuntime.OPAQUE
        }
        override fun local(name: String) = if (name in locals) locals[name] else FantomEvalRuntime.NotFound
        override fun thisObject() = self
        override fun implicitMember(name: String, args: List<Any?>?): Any? = member(self, name, args)
        override fun staticMember(type: String, name: String, args: List<Any?>?): Any? = when ("$type.$name") {
            "Str.spaces" -> " ".repeat((args!![0] as Long).toInt())
            else -> throw FantomEvalException("No slot $type.$name")
        }
        override fun member(target: Any, name: String, args: List<Any?>?): Any? = when {
            target is String && name == "upper" -> target.uppercase()
            target is String && name == "size" -> target.length.toLong()
            target is String && name == "get" -> target[(args!![0] as Long).toInt()].code.toLong()
            target is List<*> && name == "size" -> target.size.toLong()
            target is List<*> && name == "get" -> target[(args!![0] as Long).toInt()]
            target is Map<*, *> && name == "get" -> target[args!![0]]
            target is Obj && name in target.fields -> target.fields[name]
            else -> throw FantomEvalException("No slot $name")
        }
        override fun equals(a: Any?, b: Any?) = a == b
        override fun compare(a: Any?, b: Any?) = 0L
        override fun toStr(value: Any?) = value.toString()
        override fun isInstance(value: Any, type: String) = when (type.removeSuffix("?")) {
            "Str" -> value is String
            "Int" -> value is Long
            "Obj" -> true
            else -> value is Obj && value.type == type
        }
        override fun newList(items: List<Any?>) = items.toMutableList()
    }

    private fun ast(text: String): FExpr {
        val fragment = FantomExpressionCodeFragment(project, "fragment.fan", text, null)
        return FantomEvalAstBuilder.build(fragment.node)
    }

    private fun eval(text: String): Any? = FantomInterpreter(fake).eval(ast(text))

    private fun error(text: String): String =
        try { eval(text); "no error" } catch (e: FantomEvalException) { e.message!! }

    fun testTaskExpressions() {
        assertEquals(4L, eval("x + 1"))
        assertEquals(3L, eval("list.size"))
        assertEquals(1L, eval("list[0]"))
        assertEquals("ABC", eval("s.upper"))
        assertEquals(false, eval("x > 2 && n != null"))
        assertEquals(true, eval("x > 2 && s != null"))
        assertEquals("fooName", eval("f?.name ?: \"none\""))
        assertEquals("none", eval("n?.name ?: \"none\""))
    }

    fun testOperators() {
        assertEquals(7L, eval("1 + 2 * 3"))
        assertEquals(9L, eval("(1 + 2) * 3"))
        assertEquals(1L, eval("7 % 3"))
        assertEquals(2L, eval("7 / 3"))
        assertEquals(-3L, eval("-x"))
        assertEquals(5.5, eval("y + 3"))
        assertEquals(-1L, eval("1 <=> 2"))
        assertEquals(1L, eval("\"b\" <=> \"a\""))
        assertEquals(true, eval("\"a\" < \"b\""))
        assertEquals(true, eval("x >= 3"))
        assertEquals(false, eval("x != 3"))
        assertEquals("abc1", eval("s + 1"))
        assertEquals("x=3", eval("\"x=\" + x"))
        assertEquals(true, eval("!false || b"))
        assertEquals(false, eval("x == 3.0f"))
        assertEquals(255L, eval("0xff"))
        assertEquals(97L, eval("'a'"))
        assertEquals("yes", eval("b ? \"yes\" : \"no\""))
        assertEquals("Division by zero", error("x / 0"))
    }

    fun testSlotsTypesAndLiterals() {
        assertEquals(7L, eval("count"))
        assertEquals(7L, eval("this.count"))
        assertEquals("  ", eval("Str.spaces(2)"))
        assertEquals(98L, eval("s[1]"))
        assertEquals(1L, eval("map[\"a\"]"))
        assertEquals(listOf(1L, 3L), eval("[1, x]"))
        assertEquals(true, eval("s is Str"))
        assertEquals(false, eval("x is Str"))
        assertEquals(true, eval("x isnot Str"))
        assertEquals(null, eval("x as Str"))
        assertEquals("abc", eval("s as Str"))
        assertEquals(null, eval("null"))
        assertEquals("a\nb", eval("\"a\\nb\""))
    }

    /** The Evaluate box opens an editor on the fragment; the plugin's annotators must not fail on it. */
    fun testFragmentInEditorHighlightsWithoutErrors() {
        val file = myFixture.configureByText("Foo.fan", "class Foo { Void m() { x := 3; <caret>echo(x) } }")
        val context = file.findElementAt(myFixture.caretOffset)
        for (text in listOf("x + 1", "list[0].upper ?: \"none\"", "x +", "a->b", "\"v=\$x\"")) {
            val fragment = FantomCodeFragmentFactory().createPsiCodeFragment(
                com.intellij.debugger.engine.evaluation.TextWithImportsImpl(
                    com.intellij.debugger.engine.evaluation.CodeFragmentKind.EXPRESSION, text), context, project)
            myFixture.openFileInEditor(fragment.virtualFile)
            myFixture.doHighlighting()
        }
        assertTrue(FantomCodeFragmentFactory().isContextAccepted(context))
    }

    fun testUnsupportedConstructsGiveAMessage() {
        val prefix = "Not supported in the Fantom evaluator: "
        for ((text, construct) in listOf(
            "x = 4" to "assignment",
            "1..3" to "range",
            "a->b" to "-> (dynamic call)",
            "[\"a\": 1]" to "map literal",
            "\"v=\$x\"" to "Str interpolation",
            "list.each |v| { echo(v) }" to "closure",
            "x++" to "++ and --",
        )) {
            assertEquals(text, prefix + construct, error(text))
        }
        assertTrue(error("x +").startsWith("Syntax error"))
        assertEquals("Null value: cannot get 'name' (use ?. for a null-safe call)", error("n.name"))
    }
}
