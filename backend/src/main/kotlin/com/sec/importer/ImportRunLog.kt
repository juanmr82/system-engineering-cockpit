package com.sec.importer

/**
 * The live log of one active run: the last [capacity] lines, and nothing older.
 *
 * **Deliberately not persisted** (spec §11.2). A per-line log is the wrong shape for a graph — it
 * is append-only, ordered, never traversed and never joined — and writing it there would put a
 * thousand nodes per run next to the data the runs exist to produce. The graph keeps the run's
 * outcome; the lines live here until the process forgets them, and if durable logs are ever needed
 * they belong in a file.
 *
 * Bounded because the alternative is a log whose length is a function of how bad the import went:
 * the run that most needs its last lines read is the run that emits the most.
 *
 * Synchronised rather than a concurrent collection: an [ArrayDeque] with a size cap needs its two
 * operations to happen together, which no lock-free deque offers, and the contention is one writer
 * against the occasional reader.
 */
internal class ImportRunLog(private val capacity: Int = DEFAULT_CAPACITY) {

    private val lines = ArrayDeque<ImportLogLine>(capacity)

    @Synchronized
    fun add(line: ImportLogLine) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast(line)
    }

    /** A copy, so a caller iterating it cannot see the importer append mid-render. */
    @Synchronized
    fun snapshot(): List<ImportLogLine> = lines.toList()

    companion object {
        const val DEFAULT_CAPACITY: Int = 1_000
    }
}
