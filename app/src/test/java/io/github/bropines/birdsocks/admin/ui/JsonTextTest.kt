package io.github.bropines.birdsocks.admin.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTextTest {
    @Test
    fun tokensTellKeysFromStrings() {
        val text = """{"name": "office", "n": -1.5e3, "on": true, "x": null}"""
        val tokens = JsonText.tokens(text).filter { it.kind != JsonText.Kind.SPACE }
        fun of(kind: JsonText.Kind) = tokens.filter { it.kind == kind }.map { text.substring(it.start, it.end) }
        assertEquals(listOf("\"name\"", "\"n\"", "\"on\"", "\"x\""), of(JsonText.Kind.KEY))
        assertEquals(listOf("\"office\""), of(JsonText.Kind.STRING))
        assertEquals(listOf("-1.5e3"), of(JsonText.Kind.NUMBER))
        assertEquals(listOf("true", "null"), of(JsonText.Kind.LITERAL))
        assertTrue(of(JsonText.Kind.ERROR).isEmpty())
        // Every char is in exactly one token.
        assertEquals(text.length, JsonText.tokens(text).sumOf { it.end - it.start })
    }

    @Test
    fun junkIsAnErrorToken() {
        val tokens = JsonText.tokens("{\"a\": tru, @}")
        assertTrue(tokens.any { it.kind == JsonText.Kind.ERROR })
    }

    @Test
    fun problemsHaveLineAndColumn() {
        assertNull(JsonText.check("""{"a": [1, 2, {"b": "c\n\u00e9"}]}"""))
        val trailing = JsonText.check("{\n  \"a\": 1,\n}")!!
        assertEquals(3, trailing.line)
        assertEquals(1, trailing.column)
        assertTrue(trailing.message.contains("trailing comma"))
        val missingColon = JsonText.check("{\"a\" 1}")!!
        assertEquals(1, missingColon.line)
        assertEquals(6, missingColon.column)
        assertTrue(JsonText.check("")!!.message.contains("empty"))
        assertTrue(JsonText.check("[1] 2")!!.message.contains("after the value"))
        assertTrue(JsonText.check("{\"a\": 01}") != null)
        assertTrue(JsonText.check("{\"a\": \"open\n\"}")!!.message.contains("unterminated"))
        assertTrue(JsonText.check("\"\\x\"")!!.message.contains("escape"))
    }

    @Test
    fun formatIndentsAndKeepsKeyOrder() {
        assertEquals("{\n  \"z\": 1,\n  \"a\": [\n    true\n  ]\n}", JsonText.format("""{"z":1,"a":[true]}"""))
        assertNull(JsonText.format("{"))
    }

    @Test
    fun positionsBothWays() {
        val text = "ab\ncde\nf"
        assertEquals(2 to 2, JsonText.lineColumn(text, 4))
        assertEquals(4, JsonText.offsetOf(text, 2, 2))
        assertEquals(text.length, JsonText.offsetOf(text, 9, 9))
        assertEquals(listOf(0, 5), JsonText.find("Abc abc", "abc").let { listOf(it[0], it[1] + 1) })
        assertTrue(JsonText.find("abc", "").isEmpty())
    }

    @Test
    fun historyMergesTypingAndSplitsOnBoundaries() {
        var now = 0L
        val h = EditHistory("", mergeMs = 800, clock = { now })
        h.push("a"); now += 100
        h.push("ab"); now += 100
        h.push("abc")
        now += 2_000
        h.push("abc d")
        h.push("{formatted}", boundary = true)
        assertEquals("abc d", h.undo())
        assertEquals("abc", h.undo())
        assertEquals("", h.undo())
        assertFalse(h.canUndo)
        assertEquals("abc", h.redo())
        h.push("x", boundary = true)
        assertFalse(h.canRedo)
    }
}
