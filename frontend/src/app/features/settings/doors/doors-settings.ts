import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { ImportRunStore } from '../../../core/import/import-run-store';
import { COUNTER_LABELS, type ImportRun } from '../../../core/import/import.model';
import {
  DoorsSettingsApiService,
  type DoorsImportResult,
} from './doors-settings-api.service';

/** The importer this page feeds. The framework is generic; this page is not. */
const DOORS_IMPORTER = 'doors';

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
 * ## Three outcomes, not one
 *
 * A `202` is a run to watch. A `200` means this exact file was already imported — nothing runs, and
 * the page says so rather than staying silent. A `404` means the module already exists and is not
 * currently visible to this account — an access manager has to assign it a category before it can be
 * re-imported (ADR 0019 §4). All three are answered from one upload, not inferred afterwards.
 *
 * ## Real upload progress, because these files run large
 *
 * A DOORS module can carry thousands of objects at dozens of attributes each, so the picker shows
 * genuine byte-level progress while the file is in transit — see `DoorsSettingsApiService` — before
 * the run's own phase progress takes over once it starts.
 */
@Component({
  selector: 'sec-doors-settings',
  imports: [MatButtonModule, MatProgressBarModule, RouterLink],
  templateUrl: './doors-settings.html',
  styleUrl: './doors-settings.scss',
})
export class DoorsSettings {
  private readonly api = inject(DoorsSettingsApiService);
  private readonly runs = inject(ImportRunStore);

  /** The chosen file's name and text. Null until one is chosen; never persisted anywhere. */
  protected readonly file = signal<{ name: string; size: number; text: string } | null>(null);

  protected readonly reading = signal(false);
  /** Bytes-sent percentage while the upload is in flight; null before and after. */
  protected readonly uploadPercent = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);

  /** What the upload answered, kept so the page can say what the file turned out to be. */
  protected readonly result = signal<DoorsImportResult | null>(null);

  protected readonly lastRun = signal<ImportRun | null>(null);

  /** See `WindchillSettings` — false says only that this page cannot show the run's progress,
   *  never that the import failed. */
  protected readonly watching = signal(true);

  protected readonly uploading = computed(() => this.uploadPercent() !== null);

  /** The run this page started, or one already going when it loaded. */
  protected readonly activeRun = computed(() => {
    const run = this.runs.run();
    return run?.status === 'RUNNING' ? run : null;
  });

  protected readonly canImport = computed(
    () =>
      this.file() !== null && !this.uploading() && !this.reading() && this.activeRun() === null,
  );

  constructor() {
    void this.loadRuns();
  }

  /** Reads the chosen file — see `WindchillSettings` for why this happens now rather than at
   *  import time, and why `File.text()` is the right decoding. */
  protected async onFile(event: Event): Promise<void> {
    const chosen = (event.target as HTMLInputElement).files?.[0] ?? null;

    this.error.set(null);
    this.result.set(null);

    if (!chosen) {
      this.file.set(null);
      return;
    }

    this.reading.set(true);
    try {
      const text = await chosen.text();
      this.file.set({ name: chosen.name, size: chosen.size, text });
    } catch {
      this.file.set(null);
      this.error.set('That file could not be read from disk.');
    } finally {
      this.reading.set(false);
    }
  }

  /** Upload and import. The chosen file is not a staging layer (R7): it dies with this call
   *  whether the upload succeeds or fails, and there is no Save anywhere near it. */
  protected runImport(): void {
    const chosen = this.file();
    if (!chosen) return;

    this.error.set(null);
    this.result.set(null);
    this.uploadPercent.set(0);

    this.api.importExport(chosen.text).subscribe({
      next: (event) => {
        if (event.kind === 'progress') {
          this.uploadPercent.set(event.percent);
          return;
        }

        this.uploadPercent.set(null);
        this.result.set(event.result);
        this.file.set(null);

        const runId = event.result.runId;
        if (runId) {
          void this.watchRun(runId);
        } else {
          // 'skipped' started no run — refresh the last-run card so a reader can still see it
          // was the same file as before, not that nothing happened.
          void this.loadRuns();
        }
      },
      error: (cause: unknown) => {
        this.uploadPercent.set(null);
        this.error.set(cause instanceof Error ? cause.message : 'The export could not be imported.');
      },
    });
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

  private async watchRun(runId: string): Promise<void> {
    try {
      await this.runs.watch(runId);
    } catch {
      this.watching.set(false);
    }
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
