import { Component, input, output } from '@angular/core';
import { EmptyState } from '../../../../shared/empty-state/empty-state';
import type { CyclesResponse, LoopMember } from '../statistics.model';

/**
 * Band 4 — circular references (requirements-statistics.md §7).
 *
 * A **finding list, never a number**. A set of six ids does not read as a loop to anyone, so each
 * loop is drawn as its ring in order, with the closing hop marked, and every member links into
 * its Breakdown tab — which is where a loop actually gets fixed.
 *
 * No chart. A cycle is a structure, not a magnitude, and the one honest visual for it is the ring
 * itself.
 */
@Component({
  selector: 'sec-cycles-band',
  imports: [EmptyState],
  templateUrl: './cycles-band.html',
  styleUrl: './cycles-band.scss',
})
export class CyclesBand {
  readonly cycles = input<CyclesResponse | null>(null);
  readonly loading = input(false);
  readonly failed = input(false);

  readonly memberSelect = output<string>();

  /** DOORS's own id when there is one; a placeholder has none and is named in words instead. */
  protected label(member: LoopMember): string {
    return member.id ?? 'Not yet imported';
  }
}
