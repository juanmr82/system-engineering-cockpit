import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Breakdown } from './breakdown';
import type { BreakdownNode, BreakdownResponse } from './breakdown.model';

const node = (ref: string, over: Partial<BreakdownNode> = {}): BreakdownNode => ({
  ref,
  id: ref.toUpperCase(),
  level: null,
  description: `${ref} statement`,
  resolved: true,
  moduleRef: 'bW9k',
  moduleName: 'Segment requirements',
  verificationAttributes: [],
  ...over,
});

/** The reference module's SEG-REQ-1247 shape: one requirement, two parents, two roots. */
const RESPONSE: BreakdownResponse = {
  selectedRef: 'cmp1',
  // Three, and the third is the placeholder: nothing was queried above it, so it is terminal and
  // the server reports it as a root of what can be drawn.
  roots: ['sys1', 'sys9', 'gone'],
  truncated: false,
  nodes: [
    node('sys1', { level: { code: 'L1', label: 'L1 – System of Systems' } }),
    node('sys9', { level: { code: 'L1', label: 'L1 – System of Systems' } }),
    node('seg1', {
      level: { code: 'L2', label: 'L2 – Segment' },
      verificationAttributes: [{ name: 'Verification Method', value: 'Test' }],
    }),
    node('seg2', { level: { code: 'L2', label: 'L2 – Segment' } }),
    node('cmp1'),
    node('gone', { id: null, resolved: false, description: '', moduleName: 'Not imported yet' }),
  ],
  edges: [
    { from: 'cmp1', to: 'seg1', cyclic: false },
    { from: 'cmp1', to: 'seg2', cyclic: false },
    { from: 'cmp1', to: 'sys9', cyclic: false },
    { from: 'seg1', to: 'sys1', cyclic: false },
    { from: 'seg2', to: 'sys1', cyclic: false },
    { from: 'seg1', to: 'gone', cyclic: false },
  ],
};

describe('Breakdown', () => {
  let fixture: ComponentFixture<Breakdown>;
  let httpTesting: HttpTestingController;

  // fixture.nativeElement is `any`; narrowing it once here keeps every helper below typed.
  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  const renderedText = (): string => host().textContent ?? '';

  const rows = (): HTMLElement[] => Array.from(host().querySelectorAll('sec-breakdown-row'));

  const rowsFor = (id: string): HTMLElement[] =>
    rows().filter((row) => row.querySelector('.sec-bd__id')?.textContent?.trim() === id);

  const settle = async (): Promise<void> => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };

  const open = async (response: BreakdownResponse = RESPONSE): Promise<void> => {
    fixture = TestBed.createComponent(Breakdown);
    fixture.componentRef.setInput('itemRef', 'cmp1');
    fixture.detectChanges();
    httpTesting = TestBed.inject(HttpTestingController);
    httpTesting.expectOne('/api/v1/items/cmp1/breakdown').flush(response);
    await settle();
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Breakdown],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    }).compileComponents();
  });

  afterEach(() => httpTesting.verify());

  // §7: the request goes out when the tab is created, and the tree it draws is the whole forest —
  // every root, not just the selected item's own chain.
  it('loads the forest and shows every root', async () => {
    await open();

    // Labelled only because there is more than one — a single root gets no such eyebrow (§3A).
    expect(renderedText()).toContain('Root 1 of 3');
    expect(renderedText()).toContain('Root 3 of 3');
  });

  // Criterion 5, and the correction the mockup asked for: never "flows into".
  it('names every relationship "refines" and says so is a convention of this view', async () => {
    await open();

    expect(renderedText()).toContain('refines');
    expect(renderedText()).not.toContain('flows into');
    expect(renderedText()).toContain('this requirement refines its target');
  });

  /**
   * The rule this view turns on: a requirement drawn under every parent it refines, so a reviewer
   * reading the second tree does not find it missing from a decomposition it is part of.
   */
  it('draws a multi-parent requirement in every tree it belongs to', async () => {
    await open();

    // Four, not three: cmp1 refines seg1, seg2 and sys9 — and seg1 itself refines two parents, so
    // seg1's whole subtree is drawn in both of *its* trees. That cascade is the rule working, not
    // a bug: a branch that genuinely sits under two parents sits under both wherever it appears.
    expect(rowsFor('CMP1')).toHaveLength(4);
  });

  // Copies of one requirement are only readable if each names the parent it sits under.
  it('names the parent each row refines', async () => {
    await open();

    const relations = rowsFor('CMP1').map((row) =>
      row.querySelector('.sec-bd__relation')?.textContent?.trim(),
    );
    expect(relations.sort()).toEqual([
      'refines SEG1',
      'refines SEG1',
      'refines SEG2',
      'refines SYS9',
    ]);
  });

  /**
   * §1's ask: every requirement in this view shows its statement and its verification attributes,
   * without a click — including ones on a branch the reviewer never opened.
   */
  it('shows every requirement\'s text and verification box without a click', async () => {
    await open();

    const bodies = rows().filter((row) => row.querySelector('.sec-bd__body'));
    // Every resolved node has a body; the placeholder has nothing to show.
    expect(bodies).toHaveLength(rows().length - 1);
    expect(renderedText()).toContain('seg2 statement');
    expect(renderedText()).toContain('Verification Method');
    expect(renderedText()).toContain('Test');
    // Criterion 7: an absence of configuration, said quietly rather than as a finding.
    expect(renderedText()).toContain('No verification attribute defined yet for this requirement');
  });

  // ...and every one of them can be closed again.
  it('collapses a row on its twisty, hiding its body and its children', async () => {
    await open();

    const sys1 = rowsFor('SYS1')[0];
    expect(sys1.querySelector('.sec-bd__body')).not.toBeNull();

    sys1.querySelector<HTMLButtonElement>('.sec-bd__twisty')?.click();
    await settle();

    const collapsed = rowsFor('SYS1')[0];
    expect(collapsed.querySelector('.sec-bd__body')).toBeNull();
    expect(collapsed.textContent).toContain('2 children');
    // Its subtree went with it — but only *its* copy. seg1 is also drawn under the placeholder
    // root, and that copy, with everything beneath it, is untouched.
    expect(rowsFor('SEG1')).toHaveLength(1);
    expect(rowsFor('CMP1')).toHaveLength(2);
  });

  // Criterion 4: the wording the References column already uses, not "no upstream links".
  it('says "no incoming links" for a leaf', async () => {
    await open();

    expect(renderedText()).toContain('No incoming links');
    expect(renderedText()).not.toContain('upstream');
  });

  // §7: a placeholder is a leaf that is marked, not hidden.
  it('marks a not-yet-imported node', async () => {
    await open();

    expect(renderedText()).toContain('Not yet imported (Not imported yet)');
  });

  /**
   * The requirement the reviewer opened, said in words as well as in colour.
   *
   * Colour alone was not enough: this row can be drawn in more than one tree and more than once in
   * a tree, so it has to be findable halfway down a long forest rather than only recognisable once
   * you are already looking at it.
   */
  it('names the requirement the reviewer opened, on every copy of it', async () => {
    await open();

    const marked = rowsFor('CMP1').filter((row) => row.querySelector('.sec-bd__subject'));
    expect(marked).toHaveLength(4);
    expect(renderedText()).toContain('The requirement you opened');
    // The colour is the second signal, not the only one.
    expect(marked[0].querySelector('.sec-bd__card--selected')).not.toBeNull();
  });

  /**
   * A module nobody has classified still gets its square.
   *
   * Dropping the badge shifts every id in the column left by its width, which reads as a rendering
   * fault rather than as an absence — so the badge stays, empty and outlined, and says what it
   * means on hover.
   */
  it('keeps the level square when a module has no system level, and says so', async () => {
    await open();

    const unset = rowsFor('CMP1')[0].querySelector('.sec-bd__level');
    expect(unset).not.toBeNull();
    expect(unset?.textContent?.trim()).toBe('');
    expect(unset?.classList).toContain('sec-level--none');

    const set = rowsFor('SEG1')[0].querySelector('.sec-bd__level');
    expect(set?.textContent?.trim()).toBe('L2');
    expect(set?.classList).toContain('sec-level--L2');
  });

  /**
   * Nothing in the tree navigates. Following a row elsewhere would replace the tree the reviewer is
   * reading, and the tree is the point of the tab — so the twisty is the only control on a row.
   */
  it('has no clickable requirement: the twisty is the only control', async () => {
    await open();

    const controls = Array.from(host().querySelectorAll('sec-breakdown-row button'));
    expect(controls.every((button) => button.classList.contains('sec-bd__twisty'))).toBe(true);
    expect(host().querySelectorAll('sec-breakdown-row a')).toHaveLength(0);
  });

  // §6: a partial forest presented as complete is how a reviewer concludes a requirement
  // decomposes into nothing.
  it('says when the tree was truncated', async () => {
    await open({ ...RESPONSE, truncated: true });

    expect(renderedText()).toContain('Tree truncated at 6 items');
  });

  // R5, criterion 9: no internal name reaches the template, whatever the payload carries.
  it('shows no internal names', async () => {
    await open();

    expect(renderedText()).not.toContain('__');
  });
});
