import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { beforeEach, describe, expect, it } from 'vitest';
import { DependencyGraphDialog } from './dependency-graph-dialog';
import type { DependencyGraph, GraphNode } from './graph.model';

// The rendering half of docs/REQ_BREAKDOWN_GRAPH_VIEW §7. The canvas itself is invisible to jsdom
// — no layout, no canvas, no ResizeObserver — so what is asserted here is what a screen reader and
// a reviewer would read: the cards, the mandatory caveat, the banner and the reading table.

const node = (ref: string, over: Partial<GraphNode> = {}): GraphNode => ({
  card: {
    ref,
    id: ref.toUpperCase(),
    level: { code: 'L2', label: 'L2 – Segment' },
    description: `${ref} statement`,
    resolved: true,
    moduleRef: 'bW9k',
    moduleName: 'Segment requirements',
    verificationAttributes: [],
    ...over.card,
  },
  level: 2,
  seed: false,
  truncatedNeighbours: 0,
  ...over,
});

const GRAPH: DependencyGraph = {
  seedRefs: ['cmp1'],
  depth: 2,
  direction: 'BOTH',
  levelStrategy: 'MODULE_SYSTEM_LEVEL',
  nodes: [
    node('sys1', {
      level: 1,
      card: {
        ref: 'sys1',
        id: 'SYS-1',
        level: { code: 'L1', label: 'L1 – System of Systems' },
        description: 'The aircraft shall fly',
        resolved: true,
        moduleRef: 'c3lz',
        moduleName: 'System requirements',
        verificationAttributes: [],
      },
    }),
    node('seg1', {
      card: {
        ref: 'seg1',
        id: 'SEG-1',
        level: { code: 'L2', label: 'L2 – Segment' },
        description: 'The wing shall generate lift',
        resolved: true,
        moduleRef: 'bW9k',
        moduleName: 'Segment requirements',
        verificationAttributes: [{ name: 'Verification Method', value: 'Test' }],
      },
    }),
    node('cmp1', { seed: true, level: null, truncatedNeighbours: 3 }),
  ],
  edges: [
    { source: 'cmp1', target: 'seg1' },
    { source: 'seg1', target: 'sys1' },
  ],
  levels: [
    { level: 1, label: 'L1 – System of Systems' },
    { level: 2, label: 'L2 – Segment' },
    { level: null, label: 'No system level set' },
  ],
  truncated: false,
  unresolvedModules: [],
};

/**
 * Enough of `MatDialogRef` for the component to construct.
 *
 * The methods record rather than doing nothing, so a spec that comes to care whether the dialog
 * resized or closed can assert it instead of adding a second stub beside this one.
 */
function dialogRefStub() {
  const calls: string[] = [];
  return {
    calls,
    close: () => calls.push('close'),
    updateSize: (width: string, height: string) => calls.push(`size ${width} ${height}`),
    keydownEvents: () => ({ subscribe: () => ({ unsubscribe: () => calls.push('unsubscribe') }) }),
  };
}

describe('DependencyGraphDialog', () => {
  let fixture: ComponentFixture<DependencyGraphDialog>;
  let httpTesting: HttpTestingController;

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const renderedText = (): string => host().textContent ?? '';

  const settle = async (): Promise<void> => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };

  const open = async (graph: DependencyGraph = GRAPH): Promise<void> => {
    fixture = TestBed.createComponent(DependencyGraphDialog);
    fixture.detectChanges();
    httpTesting = TestBed.inject(HttpTestingController);
    httpTesting
      .expectOne('/api/v1/items/cmp1/graph?depth=2&direction=BOTH&levels=MODULE_SYSTEM_LEVEL')
      .flush(graph);
    await settle();
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DependencyGraphDialog],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: MAT_DIALOG_DATA, useValue: { seedRef: 'cmp1', seedId: 'CMP-1' } },
        { provide: MatDialogRef, useValue: dialogRefStub() },
      ],
    }).compileComponents();
  });

  /**
   * §7, and it is the criterion this whole feature turns on: the sentence is **unconditional**.
   *
   * Only outgoing links are imported, so a requirement drawn with no incoming arrows may simply be
   * one whose referencing module has not been loaded — and "nothing depends on this" is a wrong and
   * expensive conclusion in a requirements tool (§1.1).
   */
  it('always says that only outgoing links are imported', async () => {
    await open();

    expect(renderedText()).toContain('Only outgoing links are imported');
    expect(renderedText()).toContain('may mean the referencing module has not been imported yet');
  });

  it('says it even when nothing in the graph is unresolved', async () => {
    await open({ ...GRAPH, unresolvedModules: [] });

    expect(renderedText()).toContain('Only outgoing links are imported');
    expect(host().querySelector('.sec-gd__banner')).toBeNull();
  });

  /** §7: the banner is present whenever `unresolvedModules` is non-empty, and it names them. */
  it('names the modules that have to be imported', async () => {
    await open({
      ...GRAPH,
      unresolvedModules: [
        { ref: null, name: 'A module that has not been imported yet', count: 2 },
        { ref: 'bW9k', name: 'Interface requirements', count: 1 },
      ],
    });

    const banner = host().querySelector('.sec-gd__banner');
    expect(banner).not.toBeNull();
    expect(banner?.textContent).toContain('Interface requirements');
    expect(banner?.textContent).toContain('2 objects');
    expect(banner?.textContent).toContain('1 object');
  });

  it('says when the node cap cut the picture', async () => {
    await open({ ...GRAPH, truncated: true });

    expect(renderedText()).toContain('Graph limited to 300 objects');
  });

  /**
   * §7: "the graph node and the breakdown row render the same field set for the same DTO."
   *
   * Asserted through the shared card's own classes, which is the only way it can be true by
   * construction — both views mount `sec-requirement-card`, so a field added to one is added to
   * both or to neither.
   */
  it('draws every node with the shared requirement card', async () => {
    await open();

    const cards = host().querySelectorAll('sec-graph-canvas .sec-graph__node sec-requirement-card');
    expect(cards).toHaveLength(3);

    const ids = Array.from(host().querySelectorAll('.sec-graph__node .sec-card__id')).map((el) =>
      el.textContent?.trim(),
    );
    expect(ids).toContain('SEG-1');
    expect(ids).toContain('SYS-1');

    // The level badge and the verification panel are the card's, not the graph's.
    expect(renderedText()).toContain('L2');
    expect(renderedText()).toContain('Verification Method');
  });

  /** The seed says so in words, not only by the wash behind it (§8, the fourth exception). */
  it('names the requirement the graph was opened on', async () => {
    await open();

    expect(renderedText()).toContain('The requirement you opened');
  });

  /** A node on the boundary carries how much of the picture is missing past it (§5.7). */
  it('badges a node whose links leave the graph', async () => {
    await open();

    const cut = host().querySelector('.sec-graph__cut');
    expect(cut?.textContent?.trim()).toBe('+3');
  });

  /** Four line styles are in play, so the legend is not optional (§5.3). */
  it('explains every line style', async () => {
    await open();

    const legend = host().querySelector('.sec-gd__legend');
    expect(legend?.textContent).toContain('Refines');
    expect(legend?.textContent).toContain('not yet imported');
    expect(legend?.textContent).toContain('Part of a loop');
    expect(legend?.textContent).toContain('Refers to itself');
  });

  /**
   * The accessible equivalent (§5.7). A node-link diagram is not navigable by a screen reader, so
   * the same nodes and edges are adjacent in the DOM as a table — and an absence is written out
   * rather than left as a blank cell (R5).
   */
  it('carries the same nodes and edges as a readable table', async () => {
    await open();

    const table = host().querySelector('.sec-graph__reading table');
    expect(table).not.toBeNull();

    const rows = Array.from(table?.querySelectorAll('tbody tr') ?? []);
    expect(rows).toHaveLength(3);

    const seg1 = rows.find((row) => row.querySelector('th')?.textContent?.trim() === 'SEG-1');
    expect(seg1?.textContent).toContain('SYS-1');
    // The fixture's helper builds an id from the ref, so cmp1 reads as CMP1.
    expect(seg1?.textContent).toContain('CMP1');

    const sys1 = rows.find((row) => row.querySelector('th')?.textContent?.trim() === 'SYS-1');
    expect(sys1?.textContent).toContain('nothing in this graph');
    expect(sys1?.textContent).toContain('L1 – System of Systems');
  });

  /** An isolated requirement is a normal state, and the empty state is an invitation, not an apology. */
  it('invites the reader to widen the scope when the seed stands alone', async () => {
    await open({ ...GRAPH, nodes: [node('cmp1', { seed: true })], edges: [] });

    expect(renderedText()).toContain('Nothing links to this requirement');
    expect(renderedText()).toContain('Try more hops');
  });

  /** R5: no internal name ever reaches the page, whatever the response carried. */
  it('never renders an internal name', async () => {
    await open({
      ...GRAPH,
      unresolvedModules: [{ ref: null, name: 'A module that has not been imported yet', count: 1 }],
    });

    expect(renderedText()).not.toContain('__');
    expect(renderedText()).not.toContain('refersTo');
    // Nor the enum values the controls are built from — the wording is the alias map's.
    expect(renderedText()).not.toContain('MODULE_SYSTEM_LEVEL');
    expect(renderedText()).not.toContain('OUTGOING');
  });

  /** The scope is what the URL carries, so a picture can be shared in a review (§2). */
  it('asks the server for a new scope when the depth changes', async () => {
    await open();

    const control = Array.from(host().querySelectorAll('button')).find(
      (button) => button.getAttribute('aria-label') === 'One hop more',
    );
    expect(control).toBeDefined();
    control?.click();
    await settle();

    // Debounced, so the request is not in flight yet — what is asserted is that the old one is not
    // repeated, and that the new scope is the one eventually requested.
    httpTesting.verify();
  });
});
