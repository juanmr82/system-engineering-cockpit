import { Component, computed, input } from '@angular/core';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { RequirementCardDensity, RequirementCardNode } from './requirement-card.model';

/**
 * One requirement, drawn as a card — the shared unit of the Breakdown tab and the dependency graph
 * (docs/REQ_BREAKDOWN_GRAPH_VIEW §5.1).
 *
 * **Same data, same component, two layouts.** This is a hard requirement of the graph spec, not a
 * convenience: the graph is opened from the breakdown tree and shows the same requirements, so a
 * card that showed a different field set on each side would make the two views disagree about what
 * a requirement is. [density] changes padding and clamping and nothing else.
 *
 * The chrome each view puts *around* the requirement is projected rather than built in, because it
 * genuinely differs: the tree contributes a twisty, the parent it refines and its loop markers, the
 * graph contributes a truncated-neighbours badge. None of that is a fact about the requirement.
 *
 * **Nothing in it is a link.** Following one would replace the view the reader is in, which is the
 * point of both consumers.
 */
@Component({
  selector: 'sec-requirement-card',
  imports: [MatTooltipModule],
  templateUrl: './requirement-card.html',
  styleUrl: './requirement-card.scss',
  host: {
    class: 'sec-card-host',
    '[class.sec-card-host--node]': "density() === 'node'",
  },
})
export class RequirementCard {
  readonly node = input.required<RequirementCardNode>();

  readonly density = input<RequirementCardDensity>('row');

  /** The requirement the view is about, marked on every copy of it. */
  readonly subject = input(false);

  /** False hides the body — the tree's twisty, closed. */
  readonly expanded = input(true);

  /**
   * The level-of-detail switch: id and level badge only, no body at all.
   *
   * Deliberately separate from [density], which may never change which fields are shown. This one
   * does, and it is driven by the graph's zoom signal below ~50% (§5.5): body text scaled to a
   * third of its size is unreadable *and* costs the same to render, so it is dropped rather than
   * shrunk. At any zoom a reader can see the card in full, by zooming in.
   */
  readonly compact = input(false);

  /**
   * What a placeholder says instead of an id.
   *
   * A placeholder has no DOORS id — its internal name is its internal id spelled out — so naming
   * the module that has to be imported is the whole of what can honestly be shown (R5).
   */
  protected readonly notImported = computed(() => {
    const moduleName = this.node().moduleName;
    return moduleName ? `Not yet imported (${moduleName})` : 'Not yet imported';
  });

  /**
   * What the level badge says on hover, including when there is no level.
   *
   * A module nobody has classified is a normal state and the badge says so in words, rather than
   * leaving an unexplained empty square — the square itself stays, because dropping it un-aligns
   * every id in the column.
   */
  protected readonly levelTooltip = computed(
    () => this.node().level?.label ?? 'No system level set for this module',
  );

  /** A placeholder has no body: there is nothing behind it but its id and its owning module. */
  protected readonly hasBody = computed(
    () => this.expanded() && !this.compact() && this.node().resolved,
  );
}
