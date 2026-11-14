package io.github.bropines.birdsocks.admin.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * JSON as text, without Compose: the tokens the editor colours, the first
 * problem with its line and column, and the formatter. Plain JSON only —
 * NetBird's objects have no comments.
 */
object JsonText {

    enum class Kind { KEY, STRING, NUMBER, LITERAL, PUNCT, SPACE, ERROR }

    /** [start] inclusive, [end] exclusive, in chars. */
    data class Token(val kind: Kind, val start: Int, val end: Int)

    /** Where the text stops being JSON: 1-based [line] and [column], the char [offset], and why. */
    data class Problem(val offset: Int, val line: Int, val column: Int, val message: String)

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val compact = Json { prettyPrint = false }

    /**
     * Every token of [text]. A string followed by a colon is a KEY. Anything
     * that is not JSON is an ERROR token, one char at a time, so a highlighter
     * keeps going past it.
     */
    fun tokens(text: String): List<Token> {
        val out = ArrayList<Token>(text.length / 3 + 4)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == ' ' || c == '\t' || c == '\n' || c == '\r' -> {
                    val s = i
                    while (i < n && text[i].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) i++
                    out += Token(Kind.SPACE, s, i)
                }
                c == '"' -> {
                    val s = i
                    i = stringEnd(text, i)
                    out += Token(if (i > n || text.getOrNull(i - 1) != '"' || i - s < 2) Kind.ERROR else Kind.STRING, s, i.coerceAtMost(n))
                }
                c in "{}[],:" -> { out += Token(Kind.PUNCT, i, i + 1); i++ }
                c == '-' || c.isDigit() -> {
                    val s = i
                    i++
                    while (i < n && (text[i].isDigit() || text[i] in ".eE+-")) i++
                    out += Token(Kind.NUMBER, s, i)
                }
                c.isLetter() -> {
                    val s = i
                    while (i < n && text[i].isLetter()) i++
                    out += Token(if (text.substring(s, i) in LITERALS) Kind.LITERAL else Kind.ERROR, s, i)
                }
                else -> { out += Token(Kind.ERROR, i, i + 1); i++ }
            }
        }
        // A string whose next non-space token is ':' is a key.
        var next: Token? = null
        for (k in out.indices.reversed()) {
            val t = out[k]
            if (t.kind == Kind.SPACE) continue
            if (t.kind == Kind.STRING && next != null && next.kind == Kind.PUNCT && text[next.start] == ':') out[k] = t.copy(kind = Kind.KEY)
            next = t
        }
        return out
    }

    /** The index just past the string starting at [start] (a '"'); past the end when unterminated or broken by a newline. */
    private fun stringEnd(text: String, start: Int): Int {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                '\n' -> return i
                else -> i++
            }
        }
        return text.length
    }

    private val LITERALS = setOf("true", "false", "null")

    /** The first problem in [text], or null when it is one JSON value. */
    fun check(text: String): Problem? = Checker(text).run()

    /** [text] parsed, or null when it is not JSON. */
    fun parse(text: String): JsonElement? = if (check(text) != null) null else runCatching { compact.parseToJsonElement(text) }.getOrNull()

    /** [text] re-indented, two spaces, key order kept; null when it is not JSON. */
    fun format(text: String): String? = parse(text)?.let(::format)

    fun format(element: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), element)

    /** 1-based line and column of [offset] in [text]. */
    fun lineColumn(text: String, offset: Int): Pair<Int, Int> {
        var line = 1
        var col = 1
        for (i in 0 until offset.coerceIn(0, text.length)) {
            if (text[i] == '\n') { line++; col = 1 } else col++
        }
        return line to col
    }

    /** The char offset of 1-based [line] and [column]; clamped to the text. */
    fun offsetOf(text: String, line: Int, column: Int): Int {
        var l = 1
        var i = 0
        while (l < line && i < text.length) {
            if (text[i] == '\n') l++
            i++
        }
        var c = 1
        while (c < column && i < text.length && text[i] != '\n') { i++; c++ }
        return i
    }

    /** Start offsets of every case-insensitive occurrence of [query] in [text]. */
    fun find(text: String, query: String): List<Int> {
        if (query.isEmpty()) return emptyList()
        val out = mutableListOf<Int>()
        var i = text.indexOf(query, 0, ignoreCase = true)
        while (i >= 0) {
            out += i
            i = text.indexOf(query, i + query.length.coerceAtLeast(1), ignoreCase = true)
        }
        return out
    }

    /** A recursive-descent check that knows where it stopped; the messages are short and English, shown with line and column. */
    private class Checker(val text: String) {
        var i = 0

        fun run(): Problem? = try {
            space()
            if (i >= text.length) fail("empty document")
            value(0)
            space()
            if (i < text.length) fail("unexpected '${text[i]}' after the value")
            null
        } catch (p: ProblemAt) {
            val (line, col) = lineColumn(text, p.offset)
            Problem(p.offset, line, col, p.message ?: "not JSON")
        }

        class ProblemAt(val offset: Int, message: String) : Exception(message)

        fun fail(msg: String, at: Int = i): Nothing = throw ProblemAt(at, msg)

        fun space() {
            while (i < text.length && text[i].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) i++
        }

        fun value(depth: Int) {
            if (depth > 512) fail("nested too deep")
            if (i >= text.length) fail("unexpected end")
            when (val c = text[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> word("true")
                'f' -> word("false")
                'n' -> word("null")
                else -> if (c == '-' || c.isDigit()) num() else fail("unexpected '$c'")
            }
        }

        fun obj(depth: Int) {
            i++
            space()
            if (i < text.length && text[i] == '}') { i++; return }
            while (true) {
                space()
                if (i >= text.length) fail("unexpected end in an object")
                if (text[i] != '"') fail(if (text[i] == '}') "trailing comma" else "expected a key in quotes")
                str()
                space()
                if (i >= text.length || text[i] != ':') fail("expected ':'")
                i++
                space()
                value(depth + 1)
                space()
                if (i >= text.length) fail("unexpected end in an object")
                when (text[i]) {
                    ',' -> i++
                    '}' -> { i++; return }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        fun arr(depth: Int) {
            i++
            space()
            if (i < text.length && text[i] == ']') { i++; return }
            while (true) {
                space()
                if (i < text.length && text[i] == ']') fail("trailing comma")
                value(depth + 1)
                space()
                if (i >= text.length) fail("unexpected end in an array")
                when (text[i]) {
                    ',' -> i++
                    ']' -> { i++; return }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        fun str() {
            val start = i
            i++
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '"' -> { i++; return }
                    c == '\\' -> {
                        val e = text.getOrNull(i + 1) ?: fail("unterminated string", start)
                        when (e) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> i += 2
                            'u' -> {
                                if (i + 6 > text.length || !text.substring(i + 2, i + 6).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) fail("bad \\u escape")
                                i += 6
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    c == '\n' -> fail("unterminated string", start)
                    c < ' ' -> fail("control character in a string")
                    else -> i++
                }
            }
            fail("unterminated string", start)
        }

        fun word(w: String) {
            if (!text.startsWith(w, i)) fail("unexpected '${text[i]}'")
            i += w.length
        }

        fun num() {
            val start = i
            if (text[i] == '-') i++
            if (i >= text.length || !text[i].isDigit()) fail("bad number", start)
            if (text[i] == '0') i++ else while (i < text.length && text[i].isDigit()) i++
            if (i < text.length && text[i] == '.') {
                i++
                if (i >= text.length || !text[i].isDigit()) fail("bad number", start)
                while (i < text.length && text[i].isDigit()) i++
            }
            if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
                i++
                if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
                if (i >= text.length || !text[i].isDigit()) fail("bad number", start)
                while (i < text.length && text[i].isDigit()) i++
            }
        }
    }
}

/**
 * Undo and redo for a text field, in whole steps: typing within [mergeMs] of
 * the last edit joins it, so one undo takes back a word, not a letter. The
 * values are whatever the editor keeps (text plus selection); [S] must be
 * comparable by equality.
 */
class EditHistory<S>(initial: S, private val limit: Int = 200, private val mergeMs: Long = 800, private val clock: () -> Long = System::currentTimeMillis) {
    private val undo = ArrayDeque<S>()
    private val redo = ArrayDeque<S>()
    var current: S = initial
        private set
    private var lastEdit = 0L

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    /** A new state from typing; [boundary] forces a step of its own (a paste, Format). */
    fun push(next: S, boundary: Boolean = false) {
        if (next == current) return
        val now = clock()
        if (boundary || undo.isEmpty() || now - lastEdit > mergeMs) {
            undo.addLast(current)
            if (undo.size > limit) undo.removeFirst()
        }
        current = next
        redo.clear()
        lastEdit = if (boundary) 0 else now
    }

    /** Replaces the current state without making a step: a selection that moved. */
    fun replace(next: S) {
        current = next
    }

    fun undo(): S? {
        val prev = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        current = prev
        lastEdit = 0
        return prev
    }

    fun redo(): S? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        current = next
        lastEdit = 0
        return next
    }
}
