package app.dumpkinplus.server

/**
 * The console history: the newest [max] lines, each numbered by how many lines were published before it, so remote
 * viewers can ask for "everything after line N" (see [LogSlice]).
 */
class LogBuffer(private val max: Int) {
    private val lines = ArrayDeque<String>()
    private var total = 0L

    @Synchronized
    fun append(batch: List<String>) {
        lines.addAll(batch)
        total += batch.size
        while (lines.size > max) lines.removeFirst()
    }

    /** Empties the history. Numbering carries on, so viewers notice via [LogSlice.reset]. */
    @Synchronized
    fun clear() = lines.clear()

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun slice(cursor: Long): LogSlice {
        val first = total - lines.size
        return when {
            cursor == total -> LogSlice(total, emptyList(), false)
            cursor < 0 || cursor > total || cursor < first -> LogSlice(total, lines.toList(), true)
            else -> LogSlice(total, lines.drop((cursor - first).toInt()), false)
        }
    }
}
