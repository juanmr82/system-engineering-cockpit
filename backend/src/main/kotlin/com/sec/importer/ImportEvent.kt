package com.sec.importer

/**
 * What a subscriber to a running import receives (spec §11.4).
 *
 * A sealed hierarchy rather than one wide event type, because the five kinds have genuinely
 * different shapes and different rates: `progress` arrives hundreds of times and is throttled,
 * `status` arrives exactly once and closes the stream. One type carrying every field would make
 * the client read a `percent` that means nothing on a log line.
 *
 * These are *domain* events. `api/dto/ImportDtos.kt` maps each to its wire form — the SSE `event:`
 * name and JSON `data:` — because nothing in this package should know it is being read over HTTP.
 */
public sealed interface ImportEvent {
    public val runId: String

    /** A phase started. [index] is 1-based for display; [of] is the declared phase count. */
    public data class Phase(
        override val runId: String,
        public val phase: String,
        public val label: String,
        public val index: Int,
        public val of: Int,
    ) : ImportEvent

    /**
     * Progress within a phase, with the aggregate bar already computed.
     *
     * [percent] is the whole run's, not the phase's: it is the number the top-level bar shows, and
     * computing it here — where the phase weights are — is what stops a client re-deriving it from
     * a phase list it may have fetched before the weights changed.
     */
    public data class Progress(
        override val runId: String,
        public val phase: String,
        public val current: Int,
        public val total: Int,
        public val percent: Int?,
    ) : ImportEvent

    public data class Log(override val runId: String, public val line: ImportLogLine) : ImportEvent

    /** The counters as they now stand — the whole map, not a delta, so a late line is still correct. */
    public data class Counters(
        override val runId: String,
        public val counters: Map<String, Long>,
    ) : ImportEvent

    /**
     * The run finished. **Always the last event on a stream**, after which the server closes it.
     *
     * A stream that ends without one of these is a dropped connection, and a client can tell the
     * two apart precisely because this is guaranteed to arrive first.
     */
    public data class Status(
        override val runId: String,
        public val status: ImportStatus,
        public val finishedAt: String?,
        public val warnings: Int,
        public val error: String?,
    ) : ImportEvent
}
