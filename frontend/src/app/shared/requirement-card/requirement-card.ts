import {
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  input,
  model,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
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
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
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
   * Whether the description is shown in full, rather than clamped to the few lines the `node`
   * density allows.
   *
   * A `model` rather than plain local state, because the two consumers need opposite things and
   * both are right. The Breakdown tab binds nothing and gets a self-contained toggle. The graph
   * *must* know: a card that is suddenly six lines taller is a card overlapping its neighbours,
   * because the layout was computed from a height measured before the click. So the canvas owns
   * the state, renders the same value into its measure pass, and lays out again — the toggle is a
   * height change like any other, and there is only one path that handles those.
   */
  readonly textExpanded = model(false);

  private readonly textElement = viewChild<ElementRef<HTMLElement>>('text');

  /** True while the clamp is actually hiding something. Measured on every render, never guessed. */
  private readonly clamped = signal(false);

  constructor() {
    afterRenderEffect({
      read: () => {
        // Every input that can change how much text fits, so the probe re-runs when any of them
        // does: a different requirement, a different density, a body that has just appeared.
        this.node();
        this.density();
        this.compact();
        this.expanded();
        this.textExpanded();

        const element = this.textElement()?.nativeElement;
        // jsdom reports 0 for both, which reads as "nothing is hidden" — and is right, since
        // there is no layout there to hide anything with.
        const clamped = element ? isClamped(element.scrollHeight, element.clientHeight) : false;

        untracked(() => {
          if (this.clamped() !== clamped) {
            this.clamped.set(clamped);
          }
        });
      },
    });
  }

  /**
   * Whether the show-more control is drawn at all.
   *
   * `clamped` on its own is wrong in the one direction that matters: expanding lifts the clamp, so
   * the control that expanded the card would disappear with it and leave no way back.
   */
  protected readonly canExpandText = computed(() => this.clamped() || this.textExpanded());

  protected toggleText(): void {
    this.textExpanded.update((expanded) => !expanded);
  }

  /**
   * Why a card that looks complete is marked as gone.
   *
   * Says where the fix is, because it is not here: this application has no copy of the link to
   * remove. Mirrors `Aliases.DELETED_IN_SOURCE_HINT`, which is the declaration of this wording.
   */
  protected readonly deletedTooltip =
    'This object was deleted in DOORS and the links to it were left behind. ' +
    'The link has to be removed in DOORS.';

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

/**
 * Whether a clamped element is hiding anything.
 *
 * Exported so it can be tested on numbers: jsdom has no layout, so the only honest way to cover
 * the rule is to cover the arithmetic and let a spec assert the wiring around it.
 *
 * The one-pixel tolerance is deliberate. A fractional line height leaves `scrollHeight` a rounding
 * error above `clientHeight` on text that is entirely visible, and a control offering to reveal
 * nothing is worse than no control at all.
 */
export function isClamped(scrollHeight: number, clientHeight: number): boolean {
  return scrollHeight - clientHeight > 1;
}
