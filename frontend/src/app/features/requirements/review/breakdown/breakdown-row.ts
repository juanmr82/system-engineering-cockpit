import { Component, computed, input, output } from '@angular/core';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RequirementCard } from '../../../../shared/requirement-card/requirement-card';
import type { BreakdownRow } from './breakdown.model';

/**
 * One line of the breakdown tree (docs/requirement-breakdown-tree.md §4).
 *
 * The tree is rendered as a flat list whose depth is carried by a rail rather than by nesting, so
 * this component is not recursive: the tab walks the tree once and hands down finished rows.
 *
 * **Nothing in it is a link.** A row shows its statement and its verification attributes where it
 * sits; following it elsewhere would replace the tree the reviewer is reading, and the tree is the
 * point of the tab. The only control is the twisty.
 *
 * The requirement itself is drawn by the shared card, which the dependency graph also uses as a
 * node (docs/REQ_BREAKDOWN_GRAPH_VIEW §5.1). What is left here is the *tree's* chrome — the depth
 * rail, the twisty, the parent this row refines, its loop markers — none of which is a fact about
 * the requirement, and none of which the graph wants.
 */
@Component({
  selector: 'sec-breakdown-row',
  imports: [MatTooltipModule, RequirementCard],
  templateUrl: './breakdown-row.html',
  styleUrl: './breakdown-row.scss',
  host: {
    class: 'sec-bd__row',
    // The depth rail's colour is read from --rail, which depth-rails sets off this attribute
    // (styles/_document.scss). One definition of depth, read by the card, the badge and the line.
    '[attr.data-level]': 'row().depth + 1',
    '[style.--depth]': 'row().depth',
  },
})
export class BreakdownRowComponent {
  readonly row = input.required<BreakdownRow>();

  /** The twisty. Collapsing hides this row's statement, its verification box and its children. */
  readonly toggled = output<void>();

  /**
   * The relationship line: **which** requirement this one refines, not just that it refines one.
   *
   * Naming the parent is what makes two copies of the same requirement — one under each parent it
   * refines — tell each other apart while scrolling. A root refines nothing and gets no line.
   */
  protected readonly relation = computed(() => {
    const parent = this.row().parent;
    if (!parent) {
      return null;
    }
    return parent.id ? `refines ${parent.id}` : 'refines an object that is not yet imported';
  });

  /** A row has something to collapse if it has a body of its own or anything beneath it. */
  protected readonly collapsible = computed(
    () => this.row().childCount > 0 || this.row().node.resolved,
  );

  protected readonly childLabel = computed(() => {
    const count = this.row().childCount;
    return count === 1 ? '1 child' : `${count} children`;
  });

  protected loopLabel(target: { id: string | null }): string {
    return target.id ? `loops back to ${target.id}` : 'loops back to an object not yet imported';
  }
}
