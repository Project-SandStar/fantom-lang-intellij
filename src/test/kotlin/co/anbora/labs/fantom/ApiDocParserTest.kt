package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.pod.ApiDocParser
import co.anbora.labs.fantom.lang.pod.ApiDocParser.SlotKind
import junit.framework.TestCase
import java.io.File

/** [ApiDocParser] on apidoc files taken from SkySpark 3.0.29 and Fantom pods. */
class ApiDocParserTest : TestCase() {

    private val dir = File("src/test/testData/apidoc")

    private fun parse(path: String) = ApiDocParser.parse(File(dir, path).readText())

    fun testTypeHeader() {
        val t = parse("haystack/doc/Etc.apidoc")
        assertEquals("Etc", t.name)
        assertEquals(listOf("sys::Obj"), t.base)
        assertEquals(setOf("const", "public"), t.flags)
        assertEquals("Etc.fan", t.file)
        assertEquals(15, t.line)
        assertEquals(listOf("sys::Js"), t.facets.map { it.qname })
        assertEquals("Etc is the utility methods for Haystack.", t.doc)
    }

    fun testMethodsAndFields() {
        val t = parse("haystack/doc/Etc.apidoc")
        val empty = t.slots.first { it.name == "emptyDict" }
        assertEquals(SlotKind.METHOD, empty.kind)
        assertEquals("haystack::Dict", empty.type)
        assertTrue(empty.params.isEmpty())
        assertEquals(setOf("public", "static"), empty.flags) // const dropped
        assertEquals("Get the emtpy Dict instance.", empty.doc)
        val tags = t.slots.first { it.name == "emptyTags" }
        assertEquals(SlotKind.FIELD, tags.kind)
        assertEquals("[sys::Str:sys::Obj?]", tags.type)
        assertEquals("[Str:Obj?][:]", tags.init)
        assertEquals(listOf("sys::NoDoc"), tags.facets.map { it.qname })
        val d2 = t.slots.first { it.name == "makeDict2" }
        assertEquals(listOf("n0", "v0", "n1", "v1"), d2.params.map { it.name })
        assertEquals("sys::Obj?", d2.params[1].type)
        assertTrue(d2.doc!!.startsWith("Make a Dict with two"))
    }

    fun testEnumAndFacetValues() {
        val t = parse("axon/doc/SignalType.apidoc")
        assertTrue(t.isEnum)
        assertEquals(listOf("sys::Enum", "sys::Obj"), t.base)
        val ser = t.facets.single { it.qname == "sys::Serializable" }
        assertEquals(listOf("simple" to "true"), ser.values)
        val start = t.slots.first()
        assertEquals("start", start.name)
        assertEquals(SlotKind.FIELD, start.kind)
        assertTrue("enum" in start.flags)
        assertEquals("axon::SignalType", start.type)
        assertEquals("...", start.init)
        assertNull(start.doc)
        assertEquals("err", t.slots[1].name)
    }

    fun testCtorWithDefault() {
        val t = parse("axon/doc/Fn.apidoc")
        val make = t.slots.first { it.kind == SlotKind.CTOR }
        val body = make.params.single { it.name == "body" }
        assertEquals("axon::Expr", body.type)
        assertEquals("Literal.nullVal", body.default)
        assertEquals("axon::FnParam[]", make.params.single { it.name == "params" }.type)
    }

    fun testSetter() {
        val t = parse("axon/doc/MStream.apidoc")
        assertTrue(t.slots.any { it.kind == SlotKind.FIELD && it.setter == "private" })
    }

    fun testFandocUnescape() {
        val text = "== A\nflags=public\nloc=A.fan:1/1\n\nTop\n--- not a slot\nend\n\n-- f(\n) sys::Void\nflags=public\nloc=:2/2\n\nDoc\n--- x\n"
        val t = ApiDocParser.parse(text)
        assertEquals("Top\n-- not a slot\nend", t.doc)
        assertEquals(1, t.slots.size)
        assertEquals("Doc\n-- x", t.slots[0].doc)
    }

    fun testAllSamplesParse() {
        val files = dir.walkTopDown().filter { it.extension == "apidoc" }.toList()
        assertEquals(42, files.size)
        for (f in files) {
            val t = ApiDocParser.parse(f.readText())
            assertEquals(f.nameWithoutExtension, t.name)
        }
    }
}
