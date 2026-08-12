package com.sec.api.dto

import com.sec.importer.ImportEvent
import com.sec.importer.ImportJob
import com.sec.importer.ImportLogLine
import com.sec.importer.ImportPhase
import com.sec.importer.ImportRun
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The wire shapes of the import framework (spec §11.4).
 *
 * Separate from the domain types in `com.sec.importer` on purpose: the domain types are what a job
 * writes through, and none of them should learn that they are read over HTTP. The conversion is the
 * `from…` builders at the bottom of this file, in one place, so there is one answer to what a run
 * looks like on the wire.
 *
 * **No `__`-prefixed name appears in any of these** (R5). A run's node properties are un-prefixed
 * already; its `__id` reaches the client as [ImportRunDto.runId], which is a UUID we minted and
 * carries no source identity — so unlike an item's `__id`, there is nothing here for a base64 handle
 * to hide.
 */

/** One declared step, so the console can draw the whole stepper before anything runs. */
@Serializable
public data class ImportPhaseDto(
    public val id: String,
    public val label: String,
    public val weight: Int,
)

/** A registered importer, for the console's list. */
@Serializable
public data class ImporterDto(
    public val importerId: String,
    public val name: String,
    public val phases: List<ImportPhaseDto>,
    /** The run happening right now, if one is. Null is the ordinary state. */
    public val activeRunId: String? = null,
)

/**
 * The run resource — **the reconnect and late-join source of truth**.
 *
 * A client that arrives mid-run reads this first and *then* subscribes, rather than asking the
 * stream to replay: an event stream that has to remember its own history is a second, weaker copy
 * of this resource, and the two would disagree the first time one of them dropped an event.
 */
@Serializable
public data class ImportRunDto(
    public val runId: String,
    public val importerId: String,
    public val status: String,
    public val startedAt: String,
    public val finishedAt: String? = null,
    public val phase: String? = null,
    public val phases: List<ImportPhaseDto> = emptyList(),
    /** The aggregate bar. Null before the first phase, and for a run whose phases are unknown. */
    public val percent: Int? = null,
    public val current: Int = 0,
    public val total: Int = 0,
    public val params: Map<String, String> = emptyMap(),
    public val counters: Map<String, Long> = emptyMap(),
    /** Capped at 200 entries, then one line saying how many were not kept. */
    public val warnings: List<String> = emptyList(),
    /** Message and exception class. Never a stack trace, and never a token (spec §14.5). */
    public val error: String? = null,
    /**
     * The live log, and **only for a run still in memory** — it is never persisted, so a finished
     * run's is empty rather than lost (spec §11.2).
     */
    public val log: List<ImportLogLineDto> = emptyList(),
)

@Serializable
public data class ImportLogLineDto(
    public val level: String,
    public val message: String,
    public val at: String,
)

/** `202` from a start request. The client's next move is to open this run's stream. */
@Serializable
public data class ImportStartedDto(public val runId: String)

// -- SSE payloads --------------------------------------------------------------------------------
//
// One class per `event:` name. The names are API surface — a client dispatches on them — so they
// live in ImportEventName below rather than as literals at the send site.

@Serializable
public data class PhaseEventDto(
    public val runId: String,
    public val phase: String,
    public val label: String,
    public val index: Int,
    public val of: Int,
)

@Serializable
public data class ProgressEventDto(
    public val runId: String,
    public val phase: String,
    public val current: Int,
    public val total: Int,
    public val percent: Int? = null,
)

@Serializable
public data class LogEventDto(
    public val runId: String,
    public val level: String,
    public val message: String,
    public val at: String,
)

@Serializable
public data class CountersEventDto(
    public val runId: String,
    public val counters: Map<String, Long>,
)

@Serializable
public data class StatusEventDto(
    public val runId: String,
    public val status: String,
    public val finishedAt: String? = null,
    public val warnings: Int = 0,
    public val error: String? = null,
)

/** The `event:` field values. Renaming one is a breaking change for every client. */
public object ImportEventName {
    public const val PHASE: String = "phase"
    public const val PROGRESS: String = "progress"
    public const val LOG: String = "log"
    public const val COUNTERS: String = "counters"
    public const val STATUS: String = "status"
}

// -- domain → wire -------------------------------------------------------------------------------

public fun ImportPhase.toDto(): ImportPhaseDto = ImportPhaseDto(id, label, weight)

public fun ImportLogLine.toDto(): ImportLogLineDto = ImportLogLineDto(level.name, message, at)

public fun ImportJob.toDto(activeRunId: String?): ImporterDto = ImporterDto(
    importerId = importerId,
    name = displayName,
    phases = phases.map { it.toDto() },
    activeRunId = activeRunId,
)

public fun ImportRun.toDto(log: List<ImportLogLine> = emptyList()): ImportRunDto = ImportRunDto(
    runId = runId,
    importerId = importerId,
    status = status.name,
    startedAt = startedAt,
    finishedAt = finishedAt,
    phase = phase,
    phases = phases.map { it.toDto() },
    percent = percent,
    current = current,
    total = total,
    params = params,
    counters = counters,
    warnings = warnings,
    error = error,
    log = log.map { it.toDto() },
)

/**
 * An event as its `event:` name and its `data:` payload, already encoded.
 *
 * A `when` over the sealed hierarchy rather than polymorphic serialization: the wire format is five
 * flat objects distinguished by the SSE event name, not one tagged union, and a discriminator field
 * inside the JSON would say the same thing twice.
 *
 * Encoded here rather than at the send site because `ServerSSESession.send` takes a `String` — so
 * the choice is between one typed `when` and a call site holding an `Any` that something has to
 * find a serializer for at runtime.
 */
public fun ImportEvent.toSse(): SseFrame = when (this) {
    is ImportEvent.Phase ->
        frame(ImportEventName.PHASE, PhaseEventDto(runId, phase, label, index, of))

    is ImportEvent.Progress ->
        frame(ImportEventName.PROGRESS, ProgressEventDto(runId, phase, current, total, percent))

    is ImportEvent.Log ->
        frame(ImportEventName.LOG, LogEventDto(runId, line.level.name, line.message, line.at))

    is ImportEvent.Counters ->
        frame(ImportEventName.COUNTERS, CountersEventDto(runId, counters))

    is ImportEvent.Status ->
        frame(ImportEventName.STATUS, StatusEventDto(runId, status.name, finishedAt, warnings, error))
}

/** One SSE frame: the `event:` name and the JSON that follows `data:`. */
public data class SseFrame(public val event: String, public val data: String)

private inline fun <reified T> frame(event: String, payload: T): SseFrame =
    SseFrame(event, sseJson.encodeToString(payload))

/**
 * `encodeDefaults = true`, matching the ContentNegotiation instance in `Application.kt`: a field
 * whose value happens to equal its default is still part of the contract, and a client that has to
 * defend against `percent` sometimes being absent is a client writing the same defaulting twice.
 */
private val sseJson = Json { encodeDefaults = true }
