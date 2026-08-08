import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ItemDetailPanel } from './item-detail-panel';
import type { ItemDetail } from './review.model';
import type { BreakdownResponse } from './breakdown/breakdown.model';

const detailFor = (id: string, name: string): ItemDetail => ({
  ref: 'first',
  id,
  name,
  type: 'Requirement',
  labels: ['DOORSRequirement'],
  moduleRef: 'bW9k',
  moduleName: 'Segment',
  properties: [{ label: 'Version', value: 'Current' }],
  // `REQ. Priorität` is present and empty — "the attribute exists and is empty", which is not the
  // same as absent (CLAUDE.md §11). It is listed, with its value named rather than left blank.
  attributes: { 'Object Text': name, 'REQ. Priorität': '' },
});

const breakdownFor = (ref: string): BreakdownResponse => ({
  selectedRef: ref,
  roots: [ref],
  truncated: false,
  nodes: [
    {
      ref,
      id: ref.toUpperCase(),
      level: null,
      description: `${ref} statement`,
      resolved: true,
      moduleRef: 'bW9k',
      moduleName: 'Segment',
      verificationAttributes: [],
    },
  ],
  edges: [],
});

describe('ItemDetailPanel', () => {
  let fixture: ComponentFixture<ItemDetailPanel>;
  let httpTesting: HttpTestingController;

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  const tabLabels = (): string[] =>
    Array.from(host().querySelectorAll('.mat-mdc-tab')).map(
      (tab) =>
        `${tab.textContent?.trim()}${tab.getAttribute('aria-selected') === 'true' ? ' *' : ''}`,
    );

  /**
   * Selects a tab and lets its lazy content be created — deliberately *without* `whenStable`.
   *
   * Opening the Breakdown tab starts a request, and `whenStable()` does not resolve while an
   * httpResource is in flight; it times the spec out rather than failing it. A macrotask is enough
   * to get the request issued, and the caller flushes it before settling.
   */
  const clickTab = async (label: string): Promise<void> => {
    const tab = Array.from(host().querySelectorAll<HTMLElement>('.mat-mdc-tab')).find((element) =>
      element.textContent?.includes(label),
    );
    tab?.click();
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  };

  const settle = async (): Promise<void> => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ItemDetailPanel],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    }).compileComponents();

    fixture = TestBed.createComponent(ItemDetailPanel);
    fixture.componentRef.setInput('itemRef', 'first');
    fixture.detectChanges();
    httpTesting = TestBed.inject(HttpTestingController);
    httpTesting.expectOne('/api/v1/items/first').flush(detailFor('SEG-REQ-1', 'The first requirement'));
    await settle();
  });

  afterEach(() => httpTesting.verify());

  it('shows the attributes first, with the breakdown a tab away', () => {
    expect(tabLabels()).toEqual(['Attributes *', 'Breakdown']);
    expect(host().textContent).toContain('The first requirement');
  });

  /**
   * An attribute with no value is **named**, not left as a blank line.
   *
   * `""` from DOORS means "the attribute exists and is empty" (CLAUDE.md §11), so the row has always
   * been in the list — but it rendered as an empty `<dd>`, a label with nothing beside it, which
   * reads as the panel having failed to show something rather than as an empty attribute.
   *
   * The list is the object's own. It is deliberately *not* the module's whole attribute set: that
   * cost a module-wide scan on every panel open for attributes the object does not have.
   */
  it('names an attribute whose value is empty, and lists only the object own attributes', () => {
    const rows = Array.from(host().querySelectorAll('.sec-detail__facts--attributes > div')).map(
      (row) => [
        row.querySelector('dt')?.textContent?.trim(),
        row.querySelector('dd')?.textContent?.trim(),
      ],
    );

    expect(rows).toEqual([
      ['Object Text', 'The first requirement'],
      ['REQ. Priorität', 'Empty'],
    ]);
  });

  // Never italic (CLAUDE.md §8) — the placeholder is distinguished by the non-content ink instead,
  // which is the same substitution the `absent-text` mixin already makes.
  it('marks an empty value by class, so it is quiet rather than italic', () => {
    expect(host().querySelectorAll('.sec-detail__value--empty').length).toBe(1);
  });

  /**
   * The panel leads with the object's id.
   *
   * `name` for a requirement is its `Object Text`, and on an export sanitised for sharing that is
   * the same sentence on every object — so a panel headed by the name cannot say which requirement
   * is open, which is exactly how it read against the reference module.
   */
  it('heads the panel with the object id, keeping the name as a second line', () => {
    expect(host().querySelector('.sec-detail__title')?.textContent?.trim()).toBe('SEG-REQ-1');
    expect(host().querySelector('.sec-detail__subtitle')?.textContent?.trim()).toBe(
      'The first requirement',
    );
  });

  // A module and a placeholder have no id of their own, so the name is all there is — and it must
  // not then be repeated underneath itself.
  it('falls back to the name when the object has no id, without repeating it', async () => {
    fixture.componentRef.setInput('itemRef', 'no-id');
    fixture.detectChanges();
    httpTesting
      .expectOne('/api/v1/items/no-id')
      .flush({ ...detailFor('', 'Systemanforderungen'), id: null });
    await settle();

    expect(host().querySelector('.sec-detail__title')?.textContent?.trim()).toBe(
      'Systemanforderungen',
    );
    expect(host().querySelector('.sec-detail__subtitle')).toBeNull();
  });

  // §7: the request is the Breakdown tab's, so it must not go out until the tab is opened —
  // otherwise every object opened in the panel walks the graph whether anyone looks or not.
  it('does not request a breakdown until the tab is opened', async () => {
    httpTesting.expectNone('/api/v1/items/first/breakdown');

    await clickTab('Breakdown');
    httpTesting.expectOne('/api/v1/items/first/breakdown').flush(breakdownFor('first'));
    await settle();

    expect(host().textContent).toContain('FIRST');
  });

  /**
   * The regression this spec exists for.
   *
   * Clicking another row in the review table beneath points the panel at another object, which
   * puts the detail resource back into loading. While the tab group lived inside an `@if` on that
   * resource, the group unmounted for that moment and came back with tab one selected — so a
   * reviewer reading a breakdown was silently thrown back to Attributes. Found in the browser, not
   * by a test.
   */
  it('stays on the Breakdown tab when the panel is pointed at another object', async () => {
    await clickTab('Breakdown');
    httpTesting.expectOne('/api/v1/items/first/breakdown').flush(breakdownFor('first'));
    await settle();
    expect(tabLabels()).toEqual(['Attributes', 'Breakdown *']);

    fixture.componentRef.setInput('itemRef', 'second');
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();

    // The assertion that matters, made *while both requests are still in flight* — that is the
    // window in which the tab group used to unmount.
    expect(tabLabels()).toEqual(['Attributes', 'Breakdown *']);

    httpTesting.expectOne('/api/v1/items/second').flush(detailFor('SEG-REQ-2', 'The second requirement'));
    httpTesting.expectOne('/api/v1/items/second/breakdown').flush(breakdownFor('second'));
    await settle();

    expect(tabLabels()).toEqual(['Attributes', 'Breakdown *']);
    expect(host().textContent).toContain('SECOND');
  });
});
