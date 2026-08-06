import { Component, computed, input, output } from '@angular/core';
import { MatTooltipModule } from '@angular/material/tooltip';
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
 */
@Component({
  selector: 'sec-breakdown-row',
  imports: [MatTooltipModule],
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
   * What a placeholder says instead of an id.
   *
   * The same wording as the References column, and for the same reason: a placeholder has no DOORS
   * id — its internal name is its internal id spelled out — so naming the module that has to be
   * imported is the whole of what can honestly be shown (§7, R5).
   */
  protected readonly notImported = computed(() => {
    const moduleName = this.row().node.moduleName;
    return moduleName ? `Not yet imported (${moduleName})` : 'Not yet imported';
  });

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

  /**
   * What the level badge says on hover, including when there is no level.
   *
   * A module nobody has classified is a normal state and the badge says so in words, rather than
   * leaving an unexplained empty square — the square itself stays, because dropping it un-aligns
   * every id in the column.
   */
  protected readonly levelTooltip = computed(
    () => this.row().node.level?.label ?? 'No system level set for this module',
  );

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
