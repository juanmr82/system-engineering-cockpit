import { Component, computed, effect, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ImportRunStore } from '../../../core/import/import-run-store';
import {
  COUNTER_LABELS,
  isFinished,
  type ImportPhase,
  type ImportRun,
  type Importer,
} from '../../../core/import/import.model';

/** One step of the stepper: a declared phase, and where the run is relative to it. */
interface PhaseStep {
  readonly id: string;
  readonly label: string;
  readonly state: 'done' | 'current' | 'pending';
}

/**
 * The import console (`/settings/importers`, spec §13.6).
 *
 * **Nothing in this view names a source.** It draws whatever importers the server declares and
 * whatever phases each of them declares, so DOORS and Windchill appear here the day their importers
 * register — that is the whole reason the framework is generic (spec §11).
 *
 * ## Where the live state comes from
 *
 * [ImportRunStore], which reads the run resource and then subscribes to its event stream. This
 * component holds no timer and no polling loop: every number on screen is a signal the store wrote
 * from an event, which is what makes it work under zoneless change detection.
 *
 * The history is fetched once on load and again when a run finishes, because a finished run is a
 * new row in it — and a table that only grew on reload would be a table that disagrees with the
 * console above it.
 */
@Component({
  selector: 'sec-import-runs',
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule, MatTooltipModule],
  templateUrl: './import-runs.html',
  styleUrl: './import-runs.scss',
})
export class ImportRuns {
  private readonly store = inject(ImportRunStore);

  protected readonly run = this.store.run;
  protected readonly logs = this.store.logs;
  protected readonly connected = this.store.connected;
  protected readonly isRunning = this.store.isRunning;

  protected readonly importers = signal<readonly Importer[]>([]);
  protected readonly history = signal<readonly ImportRun[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly starting = signal<string | null>(null);

  /** The run's phases as steps. Empty before a run has been opened — there is nothing to draw. */
  protected readonly steps = computed<readonly PhaseStep[]>(() => {
    const run = this.run();
    if (!run) return [];

    const phases: readonly ImportPhase[] = run.phases;
    const currentIndex = phases.findIndex((phase) => phase.id === run.phase);

    return phases.map((phase, index) => ({
      id: phase.id,
      label: phase.label,
      // A finished run has no current phase, so every step it declared is done — including the
      // ones a failure never reached. That is honest for the successful case and misleading for
      // the failed one, so a failed run says so in its own line rather than in the stepper.
      state:
        currentIndex === -1
          ? isFinished(run.status)
            ? 'done'
            : 'pending'
          : index < currentIndex
            ? 'done'
            : index === currentIndex
              ? 'current'
              : 'pending',
    }));
  });

  protected readonly counters = computed(() => {
    const run = this.run();
    if (!run) return [];

    return Object.entries(run.counters).map(([key, value]) => ({
      key,
      label: COUNTER_LABELS[key] ?? key,
      value,
    }));
  });

  /** The run whose ending has already been reacted to, so the refresh happens once per run. */
  private refreshedFor: string | null = null;

  constructor() {
    void this.load();

    // A run ending changes two things this view fetched once and cannot learn from the stream:
    // the importer is no longer running, and the history has a new row. The stream describes one
    // run and says nothing about either, so the transition into a terminal status is what re-reads
    // them — without this the console sits on "Running" after the import has finished, which is
    // exactly what a live run showed it doing.
    effect(() => {
      const current = this.run();
      if (!current || !isFinished(current.status) || this.refreshedFor === current.runId) return;

      this.refreshedFor = current.runId;
      void this.refreshImporters();
      void this.refreshHistory();
    });
  }

  protected async start(importerId: string): Promise<void> {
    this.starting.set(importerId);
    this.error.set(null);

    try {
      await this.store.start(importerId);
      await this.refreshImporters();
    } catch {
      this.error.set('Could not start the import.');
    } finally {
      this.starting.set(null);
    }
  }

  /**
   * Ask for cancellation.
   *
   * Nothing is updated here on purpose: the server answers 202 because phases stop at their next
   * checkpoint, and the `status` event is what says it happened. A console that greyed itself out
   * immediately would be claiming an import had stopped while it was still writing.
   */
  protected async cancel(): Promise<void> {
    const run = this.run();
    if (!run) return;

    try {
      await this.store.cancel(run.runId);
    } catch {
      this.error.set('Could not cancel the import. It may have already finished.');
    }
  }

  protected async open(runId: string): Promise<void> {
    await this.store.watch(runId);
    this.markSettled();
    await this.refreshHistory();
  }

  protected counterLabel(key: string): string {
    return COUNTER_LABELS[key] ?? key;
  }

  /** The duration of a finished run, in the coarsest unit that still says something. */
  protected duration(run: ImportRun): string {
    if (!run.finishedAt) return '';

    const ms = Date.parse(run.finishedAt) - Date.parse(run.startedAt);
    if (!Number.isFinite(ms) || ms < 0) return '';
    if (ms < 1000) return `${ms} ms`;
    if (ms < 60_000) return `${Math.round(ms / 100) / 10} s`;
    return `${Math.floor(ms / 60_000)} min ${Math.round((ms % 60_000) / 1000)} s`;
  }

  private async load(): Promise<void> {
    this.loading.set(true);

    try {
      await this.refreshImporters();
      await this.refreshHistory();

      // Late join: a run already going when this page opened is the one a reader came to see.
      const active = this.importers().find((importer) => importer.activeRunId !== null);
      if (active?.activeRunId) {
        await this.store.watch(active.activeRunId);
      } else if (!this.run()) {
        const last = this.history()[0];
        if (last) await this.store.watch(last.runId);
      }
      this.markSettled();
    } catch {
      this.error.set('Could not load the import history.');
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * Record an already-finished run as reacted to.
   *
   * Opening a run from the history is opening something that ended long ago, and re-reading the
   * importers and the history because of it would be two requests answering a question nobody
   * asked. Only a run that ends *while being watched* is news.
   */
  private markSettled(): void {
    const current = this.run();
    if (current && isFinished(current.status)) this.refreshedFor = current.runId;
  }

  private async refreshImporters(): Promise<void> {
    this.importers.set(await this.store.importers());
  }

  private async refreshHistory(): Promise<void> {
    this.history.set(await this.store.history());
  }
}
