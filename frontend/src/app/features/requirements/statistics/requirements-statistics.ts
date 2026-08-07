import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { ModulesApiService } from '../modules/modules-api.service';
import { StatisticsApiService } from './statistics-api.service';
import { CensusBand, type BandAnchor } from './bands/census-band';
import { CompletenessBand } from './bands/completeness-band';
import { CyclesBand } from './bands/cycles-band';
import { TraceabilityBand } from './bands/traceability-band';

/**
 * Requirements → Statistics (docs/features/requirements-statistics.md).
 *
 * The first view that reads *across* modules, and the first whose entire output is derived.
 * Nothing here writes to the graph, so — unlike Modules and Req review — it owns no buffer, needs
 * no exit guard, and has no save (R7).
 *
 * Two resources, not one: Band 4 scans the whole reference graph and the other three bands paint
 * without waiting on it (§7.4).
 */
@Component({
  selector: 'sec-requirements-statistics',
  imports: [
    CensusBand,
    CompletenessBand,
    CyclesBand,
    MatFormFieldModule,
    MatSelectModule,
    TraceabilityBand,
  ],
  templateUrl: './requirements-statistics.html',
  styleUrl: './requirements-statistics.scss',
})
export class RequirementsStatistics {
  private readonly api = inject(StatisticsApiService);
  private readonly modulesApi = inject(ModulesApiService);
  private readonly router = inject(Router);

  /** Null is the "All modules" scope, which is a real answer rather than a missing selection. */
  protected readonly moduleRef = signal<string | null>(null);

  protected readonly statistics = this.api.statistics(this.moduleRef);
  protected readonly cycles = this.api.cycles(this.moduleRef);

  // `hasValue()` before `value()`, always. A resource in an error state **throws** from `value()`,
  // so an unguarded read inside a computed the template consumes takes down the whole view — and
  // for the cycles resource that would defeat the entire point of loading Band 4 separately
  // (§7.4). Reading it as null here is what keeps one failed request to one band.
  protected readonly data = computed(() =>
    this.statistics.hasValue() ? this.statistics.value() : null,
  );

  protected readonly cyclesData = computed(() => (this.cycles.hasValue() ? this.cycles.value() : null));

  protected readonly loopCount = computed(() => this.cyclesData()?.loops.length ?? null);

  protected readonly moduleOptions = computed(() =>
    this.modulesApi.modules.hasValue() ? this.modulesApi.modules.value().rows : [],
  );

  protected readonly scopedToModule = computed(() => this.moduleRef() !== null);

  protected onScopeChange(ref: string | null): void {
    this.moduleRef.set(ref);
    // The scope lives in the URL so a finding is shareable and survives a reload (§2). Replacing
    // rather than pushing: flipping through modules is one act of looking, not a trail to walk
    // back through with the browser's Back button.
    void this.router.navigate([], {
      queryParams: { module: ref },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  /** A statistic you cannot act on is decoration — every number reaches its rows (§8). */
  protected openModule(ref: string): void {
    void this.router.navigate(['/requirements/review'], { queryParams: { module: ref } });
  }

  protected openItem(ref: string): void {
    void this.router.navigate(['/requirements/review'], { queryParams: { item: ref } });
  }

  protected scrollTo(anchor: BandAnchor): void {
    // Optional call: jsdom has no layout and does not implement scrollIntoView at all.
    document.getElementById(`sec-band-${anchor}-anchor`)?.scrollIntoView?.({ block: 'start' });
  }
}
