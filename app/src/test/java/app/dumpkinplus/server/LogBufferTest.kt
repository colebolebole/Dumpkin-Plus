package app.dumpkinplus.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBufferTest {
    @Test fun first_read_returns_everything_as_a_reset() {
        val b = LogBuffer(10); b.append(listOf("a", "b"))
        val s = b.slice(-1)
        assertEquals(listOf("a", "b"), s.lines); assertEquals(2L, s.cursor); assertTrue(s.reset)
    }

    @Test fun later_reads_return_only_new_lines() {
        val b = LogBuffer(10); b.append(listOf("a", "b"))
        val c = b.slice(-1).cursor
        assertEquals(emptyList<String>(), b.slice(c).lines)
        b.append(listOf("c", "d", "e"))
        val s = b.slice(c)
        assertEquals(listOf("c", "d", "e"), s.lines); assertFalse(s.reset); assertEquals(5L, s.cursor)
    }

    @Test fun a_viewer_that_fell_behind_the_buffer_is_reset() {
        val b = LogBuffer(3); b.append(listOf("1", "2")); val c = b.slice(-1).cursor
        b.append(listOf("3", "4", "5", "6"))
        val s = b.slice(c)
        assertTrue(s.reset); assertEquals(listOf("4", "5", "6"), s.lines); assertEquals(6L, s.cursor)
    }

    @Test fun clearing_resets_viewers_but_numbering_continues() {
        val b = LogBuffer(10); b.append(listOf("a", "b")); val c = b.slice(-1).cursor
        b.clear()
        b.append(listOf("x"))
        val s = b.slice(c)
        assertEquals(listOf("x"), s.lines); assertEquals(3L, s.cursor)
    }

    @Test fun a_cursor_from_the_future_is_a_reset() {
        val b = LogBuffer(10); b.append(listOf("a"))
        assertTrue(b.slice(500).reset)
    }

    @Test fun the_buffer_never_grows_past_its_limit() {
        val b = LogBuffer(5); b.append((1..100).map { "l$it" })
        assertEquals(listOf("l96", "l97", "l98", "l99", "l100"), b.snapshot())
    }
}
