import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { beforeEach, describe, expect, it } from 'vitest';
import { RequirementCard, isClamped } from './requirement-card';
import type { RequirementCardNode } from './requirement-card.model';

// The shared card of the Breakdown tab and the dependency graph
// (docs/REQ_BREAKDOWN_GRAPH_VIEW §5.1).
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
