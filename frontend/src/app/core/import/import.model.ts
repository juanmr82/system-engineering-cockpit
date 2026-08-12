/**
 * The wire types of the import framework (`api/dto/ImportDtos.kt`, spec §11.4).
 *
 * **Nothing here says JIRA.** The framework is source-agnostic and DOORS and Windchill are meant to
 * appear in the same console without a line of new code — `importerId` is a string, and the phases
 * a run draws are whatever that importer declared.
 */

/** One declared step, so a console can draw the whole stepper before anything runs. */
export interface ImportPhase {
  readonly id: string;
  readonly label: string;
  /** Its share of the aggregate bar. The server computes `percent`; this is here for the stepper. */
  readonly weight: number;
}

export interface Importer {
  readonly importerId: string;
  readonly name: string;
  readonly phases: readonly ImportPhase[];
  /** The run happening right now, if one is. Null is the ordinary state. */
  readonly activeRunId: string | null;
}

/** Every status the server can report. `RUNNING` is the only one that is not terminal. */
export type ImportStatus =
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'SUCCEEDED_WITH_WARNINGS'
  | 'FAILED'
  | 'CANCELLED';

export interface ImportLogLine {
  readonly level: string;
  readonly message: string;
  readonly at: string;
}

/**
 * The run resource — the reconnect and late-join source of truth (spec §11.4).
 *
 * A client that arrives mid-run reads this and *then* subscribes, rather than asking the stream to
 * replay: a stream that remembered its own history would be a second, weaker copy of this, and the
 * two would disagree the first time one dropped an event.
 */
export interface ImportRun {
  readonly runId: string;
  readonly importerId: string;
  readonly status: ImportStatus;
  readonly startedAt: string;
  readonly finishedAt: string | null;
  /** The phase id currently running. Null before the first one and after the last. */
  readonly phase: string | null;
  readonly phases: readonly ImportPhase[];
  /** The aggregate bar. Null before the first phase reports. */
  readonly percent: number | null;
  readonly current: number;
  readonly total: number;
  readonly params: Readonly<Record<string, string>>;
  readonly counters: Readonly<Record<string, number>>;
  readonly warnings: readonly string[];
  readonly error: string | null;
  /** Only for a run still in memory: the log is never persisted, so a finished run's is empty. */
  readonly log: readonly ImportLogLine[];
}

/** `202` from a start request. The client's next move is to open this run's stream. */
export interface ImportStarted {
  readonly runId: string;
}

// -- SSE payloads --------------------------------------------------------------------------------
//
// One per `event:` name. The names are API surface — the server dispatches on them too.

export interface PhaseEvent {
  readonly runId: string;
  readonly phase: string;
  readonly label: string;
  readonly index: number;
  readonly of: number;
}

export interface ProgressEvent {
  readonly runId: string;
  readonly phase: string;
  readonly current: number;
  readonly total: number;
  readonly percent: number | null;
}

export interface LogEvent {
  readonly runId: string;
  readonly level: string;
  readonly message: string;
  readonly at: string;
}

export interface CountersEvent {
  readonly runId: string;
  readonly counters: Readonly<Record<string, number>>;
}

export interface StatusEvent {
  readonly runId: string;
  readonly status: ImportStatus;
  readonly finishedAt: string | null;
  readonly warnings: number;
  readonly error: string | null;
}

/** The `event:` field values, mirroring `ImportEventName`. Renaming one breaks every client. */
export const IMPORT_EVENT = {
  phase: 'phase',
  progress: 'progress',
  log: 'log',
  counters: 'counters',
  status: 'status',
} as const;

/** True once the server will send nothing more about this run. */
export function isFinished(status: ImportStatus | undefined): boolean {
  return status !== undefined && status !== 'RUNNING';
}

/**
 * The counters the console shows, in the order it shows them, with the words a person reads.
 *
 * The server sends whatever its importer counted, so this is a *display order* and not a schema:
 * a counter absent from this map still appears, with its own key humanised. That is deliberate —
 * a new importer counting something new must not have to edit the console to be seen.
 */
export const COUNTER_LABELS: Readonly<Record<string, string>> = {
  issueTypesSeen: 'Issue types',
  fieldsSeen: 'Fields',
  issuesSeen: 'Issues',
  documentsSeen: 'Documents',
  linksSeen: 'Links',
  unresolvedCreated: 'Placeholders created',
  unresolvedResolved: 'Placeholders resolved',
  deleted: 'Deleted',
  deletedByConfig: 'Removed with a project',
};
