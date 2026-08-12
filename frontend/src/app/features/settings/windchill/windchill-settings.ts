import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { ImportRunStore } from '../../../core/import/import-run-store';
import { COUNTER_LABELS, type ImportRun } from '../../../core/import/import.model';
import {
  WindchillSettingsApiService,
  type WindchillImportStarted,
} from './windchill-settings-api.service';

/** The importer this page feeds. The framework is generic; this page is not. */
const WINDCHILL_IMPORTER = 'windchill';

/**
 * Windchill settings (`/settings/windchill`).
 *
 * ## The page is an import, and that is the whole of it
 *
 * Windchill is the one source this application is *fed* rather than connected to: an exporter
 * outside the process produces an OData `Documents` response and a person uploads it here. So there
 * is nothing to configure on this page — the host lives in the server's configuration file, where
 * every other deployment fact lives, and it is shown here read-only because a reader needs to know
 * whether a document can link back to Windchill.
 *
 * ## Choosing a file is not importing one (R7)
 *
 * The file is read into memory when it is chosen and sent when *Import* is pressed. That is one
 * gesture, one request, one transaction — and picking a file and walking away writes nothing. The
 * chosen file is not a staging layer: it is a control's value, it dies with the page, and there is
 * no Save anywhere near it.
 *
 * ## The three things it says out loud
 *
 * A refused file, in the server's own words — it knows which line was wrong. A file that carried an
 * `@odata.nextLink`, because that one imports *and* is incomplete, and the sweep is about to treat
 * it as the whole truth. And what the run removed, which is the consequence a person most needs to
 * see and the reason the last run's counters are on this page rather than only in the console.
 */
@Component({
  selector: 'sec-windchill-settings',
  imports: [MatButtonModule, MatProgressBarModule, RouterLink],
  templateUrl: './windchill-settings.html',
  styleUrl: './windchill-settings.scss',
})
export class WindchillSettings {
  private readonly api = inject(WindchillSettingsApiService);
  private readonly runs = inject(ImportRunStore);

  protected readonly health = this.api.health;

  /** The chosen file's name and text. Null until one is chosen; never persisted anywhere. */
  protected readonly file = signal<{ name: string; size: number; text: string } | null>(null);

  protected readonly reading = signal(false);
  protected readonly starting = signal(false);
  protected readonly error = signal<string | null>(null);

  /** What the upload answered, kept so the page can say what the file turned out to be. */
  protected readonly started = signal<WindchillImportStarted | null>(null);

  protected readonly lastRun = signal<ImportRun | null>(null);

  /**
   * Whether the live view of the run could be opened.
   *
   * False says one thing only: the import is running and this page cannot show its progress. It is
   * deliberately not an error — the import is unaffected — so the page points at the console
   * instead of implying something went wrong.
   */
  protected readonly watching = signal(true);

  /** The run this page started, or one already going when it loaded. */
  protected readonly activeRun = computed(() => {
    const run = this.runs.run();
    return run?.status === 'RUNNING' ? run : null;
  });

  protected readonly canImport = computed(
    () => this.file() !== null && !this.starting() && !this.reading() && this.activeRun() === null,
  );

  constructor() {
    void this.loadRuns();
  }

  /**
   * Reads the chosen file.
   *
   * Read now rather than at import time so that an unreadable file is reported while the user is
   * still looking at the picker, and so *Import* is one act with one outcome. `File.text()` decodes
   * as UTF-8, which is what the export is — a Windchill document name carries umlauts, and the
   * default Windows codepage would corrupt them.
   */
  protected async onFile(event: Event): Promise<void> {
    const chosen = (event.target as HTMLInputElement).files?.[0] ?? null;

    this.error.set(null);
    this.started.set(null);

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

  /** Upload and import — one request, and the run it answers with is watched from here. */
  protected async runImport(): Promise<void> {
    const chosen = this.file();
    if (!chosen) return;

    this.starting.set(true);
    this.error.set(null);
    this.started.set(null);

    try {
      const started = await this.api.importExport(chosen.text);
      this.started.set(started);
      // The run has begun, so the file has done its job. Clearing it is what stops a second press
      // importing the same export again by accident.
      this.file.set(null);
    } catch (cause) {
      // The server's own sentence: it knows which line of the file was wrong and this does not.
      this.error.set(cause instanceof Error ? cause.message : 'The export could not be imported.');
    } finally {
      this.starting.set(false);
    }

    // Watched *after* the outcome is settled, and never inside the try above. The import has
    // already started at this point — the server said so — so a stream that cannot be opened is a
    // console that will not update, not an import that failed. Reporting it as one would tell a
    // user to do again the thing that just worked.
    const runId = this.started()?.runId;
    if (runId) {
      try {
        await this.runs.watch(runId);
      } catch {
        this.watching.set(false);
      }
    }
  }

  protected counterEntries(run: ImportRun): { key: string; label: string; value: number }[] {
    return Object.entries(run.counters).map(([key, value]) => ({
      key,
      label: COUNTER_LABELS[key] ?? key,
      value,
    }));
  }

  /** Kilobytes, because a document export is measured in them and a byte count is unreadable. */
  protected sizeOf(bytes: number): string {
    return `${Math.max(1, Math.round(bytes / 1024))} kB`;
  }

  /**
   * The last run, and the live one if there is one.
   *
   * Read once on load rather than polled: a run started from this page is watched over SSE, and a
   * run started elsewhere is somebody else's business until this page is opened again.
   */
  private async loadRuns(): Promise<void> {
    try {
      const [history, importers] = await Promise.all([
        this.runs.history(WINDCHILL_IMPORTER, 1),
        this.runs.importers(),
      ]);

      this.lastRun.set(history[0] ?? null);

      const active = importers.find(
        (importer) => importer.importerId === WINDCHILL_IMPORTER,
      )?.activeRunId;
      if (active) await this.runs.watch(active);
    } catch {
      // The page is still usable without a history: the upload does not depend on it.
      this.lastRun.set(null);
    }
  }
}
