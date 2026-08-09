import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ErrorHandler } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideEchartsTesting, resetEchartsStub } from '../../../shared/charts/echarts-testing';
import { RequirementsStatistics } from './requirements-statistics';
import type { CyclesResponse, RequirementStatistics } from './statistics.model';

// Acceptance criteria from docs/features/requirements-statistics.md §13.
//
// Charts are canvas and therefore invisible here (ADR 0008). These specs assert the numbers via
// the census tiles and the visually-hidden data tables each chart renders beside itself — which
// is the same surface a screen reader gets, so testing it is testing the accessible view.

const STATISTICS: RequirementStatistics = {
  census: { modules: 2, items: 11, requirements: 8, openPoints: 1, links: 6, deletedLinks: 2 },
  completeness: {
    items: 11,
    itemsWithOpenPoints: 1,
    mandatoryConfigured: true,
    mandatoryViolations: 1,
    itemsMissingMandatory: 1,
    verificationConfigured: false,
    verificationViolations: 0,
    itemsMissingVerification: 0,
    itemsClean: 10,
  },
  parentage: { applicable: true, hasParent: 4, parentNotImported: 1, orphans: 1 },
  mandatoryByAttribute: [{ attribute: 'Rationale', violations: 1 }],
  openPointsByAttribute: [{ attribute: 'Object Text', violations: 1 }],
  // One target we can name and two we cannot — the real shape, and the one the display has to
  // survive: a module carries a name only once it has been imported.
  danglingTargets: [
    { ref: 'bWlzc2luZy1uYW1lZA', name: 'Interface control document' },
    { ref: 'bWlzc2luZy1h', name: null },
    { ref: 'bWlzc2luZy1i', name: null },
  ],
  modulesWithoutSystemLevel: ['Unclassified requirements'],
  truncated: false,
  modules: [
    {
      ref: 'bW9kdWxlLWwx',
      name: 'Segment requirements',
      systemLevel: { code: 'L1', label: 'L1 – System of Systems' },
      completeness: {
        items: 9,
        itemsWithOpenPoints: 1,
        mandatoryConfigured: true,
        mandatoryViolations: 1,
        itemsMissingMandatory: 1,
        verificationConfigured: true,
        verificationViolations: 1,
        itemsMissingVerification: 1,
        itemsClean: 8,
      },
      parentage: { applicable: true, hasParent: 4, parentNotImported: 1, orphans: 1 },
      mandatoryByAttribute: [{ attribute: 'Rationale', violations: 1 }],
      openPointsByAttribute: [{ attribute: 'Object Text', violations: 1 }],
      links: 6,
      danglingLinks: 1,
      deletedLinks: 2,
      truncated: false,
    },
    {
      ref: 'bW9kdWxlLWww',
      name: 'Customer requirements',
      systemLevel: { code: 'L0', label: 'L0 – Customer' },
      completeness: {
        items: 2,
        itemsWithOpenPoints: 0,
        mandatoryConfigured: false,
        mandatoryViolations: 0,
        itemsMissingMandatory: 0,
        verificationConfigured: false,
        verificationViolations: 0,
        itemsMissingVerification: 0,
        itemsClean: 2,
      },
      parentage: { applicable: false, hasParent: 0, parentNotImported: 0, orphans: 0 },
      mandatoryByAttribute: [],
      openPointsByAttribute: [],
      links: 0,
      danglingLinks: 0,
      deletedLinks: 0,
      truncated: false,
    },
  ],
};

const LOOPS: CyclesResponse = {
  edgesExamined: 6,
  truncated: false,
  loops: [
    {
      ring: [
        {
          ref: 'bDEtcmVxLTQ',
          id: 'L1-4',
          name: 'A refines B',
          moduleRef: 'bW9kdWxlLWwx',
          moduleName: 'Segment requirements',
          systemLevel: { code: 'L1', label: 'L1 – System of Systems' },
        },
        {
          ref: 'bDEtcmVxLTU',
          id: 'L1-5',
          name: 'B refines A',
          moduleRef: 'bW9kdWxlLWwx',
          moduleName: 'Segment requirements',
          systemLevel: { code: 'L1', label: 'L1 – System of Systems' },
        },
      ],
      others: [],
    },
  ],
};

const NO_LOOPS: CyclesResponse = { loops: [], edgesExamined: 6, truncated: false };

describe('RequirementsStatistics', () => {
  let fixture: ComponentFixture<RequirementsStatistics>;
  let httpTesting: HttpTestingController;
  let reportedErrors: unknown[];

  // Whitespace-normalised, because an `@if` inline in a sentence leaves a space on each side of
  // the block. HTML collapses the pair on render, so it is invisible to the reader and to a
  // screen reader — but `textContent` keeps both, and a spec asserting on prose should be
  // asserting what is read, not how the template was indented.
  const text = (): string =>
    (fixture.nativeElement.textContent as string).replace(/\s+/g, ' ');

  // Not `whenStable()`: this view holds two httpResources, and with either in flight `whenStable`
  // never resolves — the spec times out instead of failing on its assertion. A macrotask is what
  // lets a resource actually issue its request.
  const settle = async (): Promise<void> => {
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  };

  /** The module list and level vocabulary are shared resources, not this view's subject. */
  const flushSharedResources = (): void => {
    httpTesting.match('/api/v1/modules').forEach((request) =>
      request.flush({
        rows: [
          {
            ref: 'bW9kdWxlLWwx',
            name: 'Segment requirements',
            lastModified: '',
            path: '',
            systemLevel: null,
          },
        ],
      }),
    );
    httpTesting
      .match('/api/v1/config/system-levels')
      .forEach((request) => request.flush({ levels: [] }));
  };

  const flushStatistics = (body = STATISTICS, url = '/api/v1/statistics/requirements'): void => {
    httpTesting.expectOne(url).flush(body);
  };

  const flushCycles = (
    body = LOOPS,
    url = '/api/v1/statistics/requirements/cycles',
  ): void => {
    httpTesting.expectOne(url).flush(body);
  };

  beforeEach(async () => {
    resetEchartsStub();
    reportedErrors = [];
    await TestBed.configureTestingModule({
      imports: [RequirementsStatistics],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideNoopAnimations(),
        provideEchartsTesting(),
        // A failed httpResource both captures its error and reports it to the global handler.
        // Collecting rather than silencing: the failure path below asserts what was reported, so
        // this stub cannot quietly swallow a real error in any of the other specs.
        {
          provide: ErrorHandler,
          useValue: { handleError: (error: unknown) => reportedErrors.push(error) },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(RequirementsStatistics);
    httpTesting = TestBed.inject(HttpTestingController);
    await settle();
    flushSharedResources();
  });

  afterEach(() => {
    httpTesting.verify();
  });

  // Criterion 2, first half: the default scope is every module, and it is a real answer rather
  // than a missing selection — so it carries no query parameter.
  it('asks for every module by default', async () => {
    flushStatistics();
    flushCycles();
    await settle();
    expect(text()).toContain('Census');
  });

  it('renders the census', async () => {
    flushStatistics();
    flushCycles();
    await settle();

    expect(text()).toContain('Items');
    expect(text()).toContain('11');
    expect(text()).toContain('Requirements');
    expect(text()).toContain('8');
  });

  // §7.4 — the two resources are independent, and the loop tile must not claim "0" before the
  // count exists. "No loops found" and "not counted yet" are opposite claims.
  it('shows the loop count as unknown until the cycles request answers', async () => {
    flushStatistics();
    await settle();
    expect(text()).toContain('—');
    expect(text()).toContain('Still checking');

    flushCycles();
    await settle();
    expect(text()).not.toContain('Still checking');
  });

  // Criterion 11.
  it('says so plainly when there are no circular references', async () => {
    flushStatistics();
    flushCycles(NO_LOOPS);
    await settle();
    expect(text()).toContain('No circular references found');
  });

  // Criterion 9/§7.3 — a loop is a finding list, drawn as a ring, not a number.
  it('draws each loop as a ring whose last hop closes it', async () => {
    flushStatistics();
    flushCycles();
    await settle();

    expect(text()).toContain('L1-4');
    expect(text()).toContain('L1-5');
    expect(text()).toContain('refines the first, closing the loop');
  });

  // A failure in the edge scan degrades one band, never the page (§7.4).
  it('keeps the other bands when loop detection fails', async () => {
    flushStatistics();
    httpTesting
      .expectOne('/api/v1/statistics/requirements/cycles')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    await settle();

    expect(text()).toContain('The reference check did not finish');
    // The census, from the other resource, is untouched — that is the whole point of §7.4.
    expect(text()).toContain('11');

    // Nothing escapes to the global handler. This assertion is the one that matters: a resource
    // in an error state *throws* from `value()`, so an unguarded read inside a computed the
    // template consumes tears down the whole view. That is exactly what happened before
    // `hasValue()` guarded it, and this is what stops it coming back.
    expect(reportedErrors).toEqual([]);
  });

  // Criterion 7 — the distinction the configured flags exist for.
  it('reads an unconfigured check as unconfigured, never as clean', async () => {
    flushStatistics();
    flushCycles();
    await settle();
    expect(text()).toContain('No verification attribute has been chosen yet');
  });

  // Criterion 8.
  it('names the modules left out of the orphan count', async () => {
    flushStatistics();
    flushCycles();
    await settle();
    expect(text()).toContain('no system level is set for them');
    expect(text()).toContain('Unclassified requirements');
  });

  it('reports links into objects that have not been imported', async () => {
    flushStatistics();
    flushCycles();
    await settle();
    expect(text()).toContain('point to objects');
    expect(text()).toContain('Interface control document');
  });

  // A module that has not been imported has no name to show, and its only other identifier is a
  // `doors://` URL that R5 keeps off the screen. Listing those one per line printed one identical
  // sentence per module, which reads as a repeated row rather than as distinct modules. The count
  // is what is worth saying: it is how many imports would clear the links reported above.
  it('collapses the targets it cannot name into a count rather than repeating a sentence', () => {
    flushStatistics();
    flushCycles();
    return settle().then(() => {
      expect(text()).toContain('2 further modules cannot be named until they are imported');

      const listed = [...fixture.nativeElement.querySelectorAll('.sec-band__targets li')];
      expect(listed.map((li: HTMLElement) => li.textContent?.trim())).toEqual([
        'Interface control document',
      ]);
    });
  });

  // Criterion 13 — the hidden table is what carries the numbers where the canvas cannot.
  it('puts every chart series into a data table beside the canvas', async () => {
    flushStatistics();
    flushCycles();
    await settle();

    const tables = fixture.nativeElement.querySelectorAll('.sec-chart__data');
    expect(tables.length).toBeGreaterThan(0);
    const combined = [...tables].map((table: HTMLElement) => table.textContent).join(' ');
    expect(combined).toContain('Rationale');
    expect(combined).toContain('Object Text');
    expect(combined).toContain('Segment requirements');
  });

  // Criterion 2, second half.
  it('re-requests both endpoints, scoped, when the module changes', async () => {
    flushStatistics();
    flushCycles();
    await settle();

    fixture.componentInstance['moduleRef'].set('bW9kdWxlLWwx');
    await settle();

    flushStatistics(STATISTICS, '/api/v1/statistics/requirements?module=bW9kdWxlLWwx');
    flushCycles(LOOPS, '/api/v1/statistics/requirements/cycles?module=bW9kdWxlLWwx');
    await settle();

    expect(text()).toContain('Census');
  });
});
