import { Component, computed, effect, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { ImportRunStore } from '../../../core/import/import-run-store';
import { COUNTER_LABELS, isFinished, type ImportRun } from '../../../core/import/import.model';
import {
  DoorsSettingsApiService,
  type DoorsImportResult,
} from './doors-settings-api.service';

/** The importer this page feeds. The framework is generic; this page is not. */
const DOORS_IMPORTER = 'doors';

type DoorsQueueStatus = 'queued' | 'uploading' | 'importing' | 'succeeded' | 'skipped' | 'failed';

/**
 * One file the user has chosen, and where it stands in the queue. `id` is local identity —
 * `crypto.randomUUID()` at the moment it is read — never the filename, so choosing the same file
 * twice (or two files that happen to share a name) never collides.
 */
interface QueuedDoorsFile {
  readonly id: string;
  readonly name: string;
  readonly size: number;
  readonly text: string;
  readonly status: DoorsQueueStatus;
  /** Upload-in-transit percent while `'uploading'`; null otherwise. Once a run starts, live percent
   *  and phase are read from `ImportRunStore` directly (`liveRun`) rather than copied in here — see
   *  the class doc for why. */
  readonly percent: number | null;
  readonly runId: string | null;
  /** The outcome sentence — objects read, "unchanged", or the server's own refusal detail. */
  readonly message: string | null;
}

/**
 * DOORS settings (`/settings/doors`).
 *
 * ## A second door onto the same graph the Python importer writes (ADR 0019)
 *
 * The Python DOORS importer — a live DOORS client, a DXL export, a `.bat` wrapper — is unaffected
 * and keeps running on Windows exactly as before. This page is the other way an export becomes graph
 * data: an admin who already has the export file uploads it here and the backend imports it directly,
 * without a workstation, a DOORS client, or this application ever running that pipeline. Both write
 * the same module identity, so they can be used interchangeably during the transition.
 *
 * ## Open to every signed-in user, same as Windchill's and JIRA's settings pages
 *
 * There is no role check on this route or this page — `/settings/doors` is listed in the settings
 * menu for everyone, matching `/settings/jira` and `/settings/windchill`. The upload itself answers
 * `403` for anyone who is not `sec-admin`, which this page renders as the server's own refusal rather
 * than hiding the page pre-emptively (ADR 0019 §5).
 *
 * ## Several files, one importer, run strictly in sequence
 *
 * A user can choose more than one export at once — for a programme with several modules to bring
 * in, say — but only one DOORS import can ever be active at a time: the backend answers `409` for a
 * second run while one is going (`ImportRunService.start`), and `ImportRunStore` itself holds
 * exactly one run. So the queue below is not a convenience, it is the only shape that fits: each
 * file uploads and imports to completion (or failure) before the next one starts, and each file's
 * own row shows where it stands — queued, uploading/importing with a percent, succeeded, or failed.
 * The one progress bar still shows whichever file is currently in flight, exactly as it always has,
 * now labelled with that file's name.
 *
 * ## Why live run progress is never copied into a queue row
 *
 * The obvious shape — an `effect()` that finds the active row in `files()` and patches its
 * `percent`/`phase` from `ImportRunStore` on every tick — is a trap: `files` would then be both a
 * dependency the effect reads (to find the row) and a target it writes (to patch it), and every
 * patch creates a new array by value, which Angular's signal equality does not collapse. The effect
 * re-triggers itself, forever. `liveRun` sidesteps this entirely by never touching `files` — it
 * reads only `ImportRunStore` and the plain `processingRunId` signal below, so nothing it depends on
 * is ever written by the effect that depends on it.
 */
@Component({
  selector: 'sec-doors-settings',
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule, RouterLink],
  templateUrl: './doors-settings.html',
  styleUrl: './doors-settings.scss',
})
export class DoorsSettings {
  private readonly api = inject(DoorsSettingsApiService);
  private readonly runs = inject(ImportRunStore);

  /** Every file chosen so far, in the order it was chosen. Never persisted anywhere (R7). */
  protected readonly files = signal<readonly QueuedDoorsFile[]>([]);

  /** The queue entry currently uploading or importing, or null between files / before starting. */
  protected readonly processingId = signal<string | null>(null);

  /** The run id this page's own queue is currently watching, set the moment a run starts and
   *  cleared the moment it finishes — read-only inside the completion effect below, and the only
   *  thing that effect depends on besides the store itself (see the class doc). */
  private readonly processingRunId = signal<string | null>(null);

  protected readonly lastRun = signal<ImportRun | null>(null);

  /** See `WindchillSettings` — false says only that this page cannot show the run's progress,
   *  never that the import failed. */
  protected readonly watching = signal(true);

  /** The item currently in flight, resolved from `processingId` — what the shared progress bar
   *  and each row's label key off to know whether they are looking at the live one. */
  protected readonly active = computed(() => {
    const id = this.processingId();
    return id ? (this.files().find((f) => f.id === id) ?? null) : null;
  });

  /** The store's run, but only when it is the one this page's queue is currently tracking — never
   *  a stale previous file's run still sitting in the store, and never an externally-started one. */
  protected readonly liveRun = computed(() => {
    const run = this.runs.run();
    const runId = this.processingRunId();
    return run && runId && run.runId === runId ? run : null;
  });

  /** A run already going when this page loaded, or started by someone/something else — as opposed
   *  to one this page's own queue started, which `processingRunId` already tracks. */
  protected readonly activeRun = computed(() => {
    const run = this.runs.run();
    return run?.status === 'RUNNING' && run.runId !== this.processingRunId() ? run : null;
  });

  protected readonly canRunQueue = computed(
    () =>
      this.files().some((f) => f.status === 'queued') &&
      this.processingId() === null &&
      this.activeRun() === null,
  );

  constructor() {
    // Advances the queue once the run this page is tracking finishes. Deliberately depends on
    // nothing under `files` — see the class doc for why that matters.
    effect(() => {
      const run = this.runs.run();
      const runId = this.processingRunId();
      const id = this.processingId();
      if (!run || !runId || !id || run.runId !== runId || !isFinished(run.status)) return;

      const ok = run.status === 'SUCCEEDED' || run.status === 'SUCCEEDED_WITH_WARNINGS';
      // The 'ok' branch omits `message` on purpose — onUploadResult already set the "N objects
      // read" sentence, and this is only correcting `status`, not replacing it.
      this.patch(id, ok
        ? { status: 'succeeded', percent: null }
        : { status: 'failed', percent: null, message: run.error ?? 'The import did not finish.' });
      this.processingRunId.set(null);
      void this.loadRuns();
      void this.runNext();
    });

    void this.loadRuns();
  }

  /** Reads every chosen file and appends it to the queue — see `WindchillSettings` for why this
   *  happens now rather than at import time, and why `File.text()` is the right decoding. A file
   *  that cannot be read is still added, failed outright, rather than silently dropped or blocking
   *  the rest of the batch. Appends rather than replacing, so a second picker interaction adds to
   *  whatever is already queued or already finished. */
  protected async onFiles(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const chosen = Array.from(input.files ?? []);
    input.value = ''; // so choosing the same file again still fires 'change'
    if (chosen.length === 0) return;

    const read = await Promise.all(
      chosen.map(async (file): Promise<QueuedDoorsFile> => {
        const base = {
          id: crypto.randomUUID(),
          name: file.name,
          size: file.size,
          runId: null,
          percent: null,
          phase: null,
        };
        try {
          const text = await file.text();
          return { ...base, text, status: 'queued', message: null };
        } catch {
          return { ...base, text: '', status: 'failed', message: 'That file could not be read from disk.' };
        }
      }),
    );

    this.files.update((list) => [...list, ...read]);
  }

  /** Drops one file from the queue. Only offered while it is `'queued'` — nothing in flight or
   *  already finished can be removed, so this never has to cancel a request. */
  protected remove(id: string): void {
    this.files.update((list) => list.filter((f) => f.id !== id));
  }

  /** Starts the queue, or does nothing if it is already running or empty — `canRunQueue` guards
   *  the button itself, this guards the call directly too. */
  protected runImport(): void {
    if (this.canRunQueue()) void this.runNext();
  }

  /** Only one file is ever `'importing'` at a time — the one `processingId` names — so this reads
   *  its live phase/percent straight from `liveRun()` rather than from anything stored on `item`. */
  protected statusLabel(item: QueuedDoorsFile): string {
    switch (item.status) {
      case 'queued':
        return 'Queued';
      case 'uploading':
        return `Uploading — ${item.percent ?? 0}%`;
      case 'importing': {
        const run = this.liveRun();
        const phase = run?.phase ?? 'starting';
        return `Importing — ${phase}${run?.percent !== null && run?.percent !== undefined ? `, ${run.percent}%` : ''}`;
      }
      case 'succeeded':
        return item.message ?? 'Imported';
      case 'skipped':
        return item.message ?? 'Unchanged since its last import';
      case 'failed':
        return item.message ? `Failed — ${item.message}` : 'Failed';
    }
  }

  protected counterEntries(run: ImportRun): { key: string; label: string; value: number }[] {
    return Object.entries(run.counters).map(([key, value]) => ({
      key,
      label: COUNTER_LABELS[key] ?? key,
      value,
    }));
  }

  /** Kilobytes, because a module export is measured in them and a byte count is unreadable. */
  protected sizeOf(bytes: number): string {
    return `${Math.max(1, Math.round(bytes / 1024))} kB`;
  }

  /** Uploads and imports the next queued file. The effect in the constructor picks up a started
   *  run's completion and calls back here; a `'skipped'` result or an outright request failure has
   *  no run to wait on, so it advances the queue directly. */
  private async runNext(): Promise<void> {
    const next = this.files().find((f) => f.status === 'queued');
    if (!next) {
      this.processingId.set(null);
      return;
    }

    this.processingId.set(next.id);
    this.patch(next.id, { status: 'uploading', percent: 0 });

    this.api.importExport(next.text).subscribe({
      next: (event) => {
        if (event.kind === 'progress') {
          this.patch(next.id, { percent: event.percent });
          return;
        }
        this.onUploadResult(next.id, event.result);
      },
      error: (cause: unknown) => {
        this.patch(next.id, {
          status: 'failed',
          percent: null,
          message: cause instanceof Error ? cause.message : 'The export could not be imported.',
        });
        void this.runNext();
      },
    });
  }

  private onUploadResult(id: string, result: DoorsImportResult): void {
    if (!result.runId) {
      // 'skipped' started no run — nothing to watch, so this file is done and the queue moves on.
      this.patch(id, {
        status: 'skipped',
        percent: null,
        message: `'${result.moduleName}' is unchanged since its last import.`,
      });
      void this.loadRuns();
      void this.runNext();
      return;
    }

    this.patch(id, {
      status: 'importing',
      percent: null,
      runId: result.runId,
      message: `${result.objects} object${result.objects === 1 ? '' : 's'} read from the file.`,
    });
    this.processingRunId.set(result.runId);

    this.runs.watch(result.runId).catch(() => this.watching.set(false));
  }

  private patch(id: string, fields: Partial<QueuedDoorsFile>): void {
    this.files.update((list) => list.map((f) => (f.id === id ? { ...f, ...fields } : f)));
  }

  /** The last run, and the live one if there is one — see `WindchillSettings` for why this is
   *  read once on load rather than polled. */
  private async loadRuns(): Promise<void> {
    try {
      const [history, importers] = await Promise.all([
        this.runs.history(DOORS_IMPORTER, 1),
        this.runs.importers(),
      ]);

      this.lastRun.set(history[0] ?? null);

      const active = importers.find((importer) => importer.importerId === DOORS_IMPORTER)?.activeRunId;
      if (active) await this.runs.watch(active);
    } catch {
      this.lastRun.set(null);
    }
  }
}
