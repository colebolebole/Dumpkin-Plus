package app.dumpkinplus.server

/**
 * A chunk of console output. [cursor] is the number of lines ever published; a viewer sends it back to get only newer
 * lines. [reset] means the viewer's position was no longer valid (console cleared, or it fell too far behind), so
 * [lines] is the whole current buffer and the viewer should replace what it shows.
 */
data class LogSlice(val cursor: Long, val lines: List<String>, val reset: Boolean)
