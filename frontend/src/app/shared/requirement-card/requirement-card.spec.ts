import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { beforeEach, describe, expect, it } from 'vitest';
import { RequirementCard, isClamped } from './requirement-card';
import type { RequirementCardNode } from './requirement-card.model';

// The shared card of the Breakdown tab and the dependency graph
// (docs/REQ_BREAKDOWN_GRAPH_VIEW.md §5.1).
//
// **jsdom has no layout**, so it reports every height as 0 and nothing is ever measured as
// clamped here. That is not a gap to be worked around: the arithmetic is covered on numbers, and
// what the DOM assertions cover is the half that a wrong binding would break — that a card told
// its text is expanded says so, and offers the way back.

const NODE: RequirementCardNode = {
  ref: 'c2VnMQ',
  id: 'SEG-REQ-1249',
  level: { code: 'L2', label: 'L2 – Segment' },
  description: 'The segment shall maintain attitude control throughout the ascent phase.',
  resolved: true,
  deletedInSource: false,
  moduleRef: 'bW9k',
  moduleName: 'Segment requirements',
  verificationAttributes: [],
};

describe('isClamped', () => {
  it('is false when the text fits', () => {
    expect(isClamped(96, 96)).toBe(false);
  });

  /**
   * A fractional line height leaves `scrollHeight` a rounding error above `clientHeight` on text
   * that is entirely visible, and a control offering to reveal nothing is worse than no control.
   */
  it('ignores a sub-pixel overhang', () => {
    expect(isClamped(97, 96)).toBe(false);
  });

  it('is true when a line is hidden', () => {
    expect(isClamped(128, 96)).toBe(true);
  });

  /** jsdom's answer, and the right one: no layout means nothing has been hidden by one. */
  it('reads an unlaid-out element as showing everything', () => {
    expect(isClamped(0, 0)).toBe(false);
  });
});

describe('RequirementCard', () => {
  let fixture: ComponentFixture<RequirementCard>;

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const toggle = (): HTMLButtonElement | null => host().querySelector('.sec-card__more');

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RequirementCard],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    }).compileComponents();

    fixture = TestBed.createComponent(RequirementCard);
    fixture.componentRef.setInput('node', NODE);
    fixture.componentRef.setInput('density', 'node');
    fixture.detectChanges();
  });

  /** Nothing is clamped without layout, so nothing offers to unclamp it. */
  it('offers no control while the text is fully visible', () => {
    expect(toggle()).toBeNull();
    expect(host().querySelector('.sec-card__text--full')).toBeNull();
  });

  it('shows the text in full when it is told to', () => {
    fixture.componentRef.setInput('textExpanded', true);
    fixture.detectChanges();

    expect(host().querySelector('.sec-card__text--full')).not.toBeNull();
  });

  /**
   * The control has to survive its own effect. Expanding lifts the clamp, so a control drawn only
   * while the text is clamped would vanish at the moment it worked and leave no way back.
   */
  it('keeps the control once expanded, and it says how to get back', () => {
    fixture.componentRef.setInput('textExpanded', true);
    fixture.detectChanges();

    const control = toggle();
    expect(control).not.toBeNull();
    expect(control?.getAttribute('aria-expanded')).toBe('true');
    expect(control?.getAttribute('aria-label')).toBe('Show less of this text');
  });

  /** The card works unbound — the Breakdown tab passes no state and gets a working toggle. */
  it('collapses again when the control is pressed', () => {
    fixture.componentRef.setInput('textExpanded', true);
    fixture.detectChanges();

    toggle()?.click();
    fixture.detectChanges();

    expect(fixture.componentInstance.textExpanded()).toBe(false);
    expect(host().querySelector('.sec-card__text--full')).toBeNull();
  });

  /** R5: the control is chrome, and chrome speaks the alias map's language or none. */
  it('never renders an internal name', () => {
    fixture.componentRef.setInput('textExpanded', true);
    fixture.detectChanges();

    expect(host().textContent ?? '').not.toContain('__');
  });
});

/**
 * The two ways a card can be about something that is not really there.
 *
 * They are opposite states that a reader has to be able to tell apart, because only one of them
 * is fixed by importing. A placeholder is waiting for an import. An object DOORS deleted is
 * waiting for someone to open DOORS and remove the link that still reaches it — and it renders in
 * full, because it was imported and everything it shows is real except its continued existence.
 */
describe('RequirementCard, deleted in DOORS', () => {
  let fixture: ComponentFixture<RequirementCard>;

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const text = (): string => (host().textContent ?? '').replace(/\s+/g, ' ').trim();

  const mount = async (node: RequirementCardNode): Promise<void> => {
    await TestBed.configureTestingModule({
      imports: [RequirementCard],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    }).compileComponents();

    fixture = TestBed.createComponent(RequirementCard);
    fixture.componentRef.setInput('node', node);
    fixture.componentRef.setInput('density', 'node');
    fixture.detectChanges();
  };

  it('says the object is gone, and still shows everything it knows about it', async () => {
    await mount({ ...NODE, deletedInSource: true });

    expect(text()).toContain('Deleted in DOORS');
    // The id and the statement are the point of keeping the ghost: without them the card cannot
    // say *which* requirement went away.
    expect(host().querySelector('.sec-card__id')?.textContent?.trim()).toBe('SEG-REQ-1249');
    expect(text()).toContain('The segment shall maintain attitude control');
  });

  it('never calls a deleted object not yet imported', async () => {
    await mount({ ...NODE, deletedInSource: true });

    // The wording that would send a reviewer to run an import of a module they already have.
    expect(text()).not.toContain('Not yet imported');
  });

  it('leaves an ordinary card unmarked', async () => {
    await mount(NODE);

    expect(text()).not.toContain('Deleted in DOORS');
  });

  it('shows no internal name', async () => {
    await mount({ ...NODE, deletedInSource: true });

    expect(text()).not.toContain('__');
  });
});
