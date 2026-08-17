import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { AuthStore } from '../../../core/auth/auth-store';
import { settleGrid } from '../../../core/grid/grid-testing';
import type { AccessCategoryListResponse, ContainerCategories } from '../access.model';
import { AccessContainers } from './access-containers';

const CONTAINERS: ContainerCategories[] = [
  { ref: 'bW9kLTE', sourceId: 'doors', name: 'SRD', categoryRefs: ['Y2F0LTE'] },
  { ref: 'cHJvai0x', sourceId: 'jira', name: 'Avionics Board', categoryRefs: [] },
];

const CATEGORIES: AccessCategoryListResponse = {
  categories: [
    { ref: 'Y2F0LTE', key: 'doors-srd', name: 'SRD Category', description: '', everyGroup: false, objectCount: 0, groupCount: 0 },
  ],
};

class FakeAuthStore {
  isLoading = (): boolean => false;
  hasRole = (): boolean => true;
}

describe('AccessContainers', () => {
  let fixture: ComponentFixture<AccessContainers>;
  let httpTesting: HttpTestingController;
  let fakeAuthStore: FakeAuthStore;

  const renderedText = (): string => fixture.nativeElement.textContent;

  const require = <T extends HTMLElement>(selector: string): T => {
    const found = fixture.nativeElement.querySelector(selector) as T | null;
    if (!found) {
      throw new Error(`No element matched ${selector}`);
    }
    return found;
  };

  const editButtonFor = (name: string): HTMLButtonElement =>
    require<HTMLButtonElement>(`[aria-label="Edit categories for ${name}"]`);

  const clearButtonFor = (name: string): HTMLButtonElement =>
    require<HTMLButtonElement>(`[aria-label="Clear categories for ${name}"]`);

  const settle = async (): Promise<void> => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };

  async function setUp(containers = CONTAINERS): Promise<void> {
    fakeAuthStore = new FakeAuthStore();
    await TestBed.configureTestingModule({
      imports: [AccessContainers],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: AuthStore, useValue: fakeAuthStore },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AccessContainers);
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    httpTesting.expectOne('/api/v1/access/containers?state=all').flush({ containers });
    httpTesting.match('/api/v1/access/categories').forEach((request) => request.flush(CATEGORIES));
    // AccessApiService constructs groups/unassignedContainers/defaults alongside containers on
    // injection, and AccessBadgeService (also injected, for the post-edit sidenav refresh) fires
    // its own summary request too — none of them are what this view reads directly, but every
    // stray request still has to be answered here for verify() not to see it hanging.
    httpTesting.match('/api/v1/access/groups').forEach((request) => request.flush({ groups: [] }));
    httpTesting
      .match('/api/v1/access/containers?state=unassigned')
      .forEach((request) => request.flush({ containers: [] }));
    httpTesting.match('/api/v1/access/defaults').forEach((request) => request.flush({ defaults: [] }));
    httpTesting
      .match('/api/v1/access/summary')
      .forEach((request) => request.flush({ categoryCount: 1, groupCount: 0, unassignedContainerCount: 1 }));
    await settleGrid(fixture);
  }

  afterEach(() => httpTesting.verify());

  it('refuses a caller without the Access manager role, without ever requesting containers', async () => {
    fakeAuthStore = new FakeAuthStore();
    fakeAuthStore.hasRole = () => false;
    await TestBed.configureTestingModule({
      imports: [AccessContainers],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: AuthStore, useValue: fakeAuthStore },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AccessContainers);
    httpTesting = TestBed.inject(HttpTestingController);
    await settle();

    expect(renderedText()).toContain('Access manager role');
    httpTesting.expectNone('/api/v1/access/containers?state=all');
  });

  it('shows the empty state when nothing has been imported', async () => {
    await setUp([]);
    expect(renderedText()).toContain('Nothing imported yet');
  });

  describe('with containers imported', () => {
    beforeEach(() => setUp());

    it('lists every container with its source and resolved category names, or "Not yet assigned"', () => {
      expect(renderedText()).toContain('SRD');
      expect(renderedText()).toContain('doors');
      expect(renderedText()).toContain('SRD Category');
      expect(renderedText()).toContain('Avionics Board');
      expect(renderedText()).toContain('Not yet assigned');
    });

    it('only offers Clear on a row that already carries a category', () => {
      expect(() => clearButtonFor('SRD')).not.toThrow();
      expect(() => clearButtonFor('Avionics Board')).toThrow();
    });

    it('filters by search across name, source and category names', async () => {
      require<HTMLInputElement>('.sec-access-containers__search input').value = 'avionics';
      require<HTMLInputElement>('.sec-access-containers__search input').dispatchEvent(new Event('input'));
      await new Promise((resolve) => setTimeout(resolve, 250)); // clears the 200ms debounce
      await settleGrid(fixture);

      expect(renderedText()).toContain('Avionics Board');
      expect(renderedText()).not.toContain('SRD Category');
    });

    // Pre-filled from the row's own current categories (spec §10.2 screen 5) — the one real
    // difference from the Unassigned screen's own use of the same dialog.
    it('opens Edit pre-filled with the row\'s own categories, and PUTs + reconciles + reloads on confirm', async () => {
      const openSpy = vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({
        afterClosed: () => of(['Y2F0LTE']),
      } as never);

      editButtonFor('Avionics Board').click();

      const [, config] = openSpy.mock.calls.at(-1) as [unknown, { data: { initialSelection?: string[] } }];
      expect(config.data.initialSelection).toEqual([]);

      await settle();

      const put = httpTesting.expectOne('/api/v1/access/containers/cHJvai0x/categories');
      expect(put.request.method).toBe('PUT');
      expect(put.request.body).toEqual({ categoryRefs: ['Y2F0LTE'] });
      put.flush({ categoryRefs: ['Y2F0LTE'] });
      await settle();

      const reconcile = httpTesting.expectOne('/api/v1/access/reconcile?scope=source&source=jira');
      expect(reconcile.request.method).toBe('POST');
      reconcile.flush({ sources: [{ sourceId: 'jira', propagated: 1, retracted: 0, seeded: 0 }] });
      await settle();
      await new Promise((resolve) => setTimeout(resolve, 0));

      // Both containers.reload() and accessBadge.refresh() schedule a refetch rather than issuing
      // one — the same trap named throughout this suite — so both requests have to be answered or
      // the next settle() waits on one of them forever.
      httpTesting.expectOne('/api/v1/access/containers?state=all').flush({
        containers: [{ ...CONTAINERS[1], categoryRefs: ['Y2F0LTE'] }, CONTAINERS[0]],
      });
      httpTesting
        .expectOne('/api/v1/access/summary')
        .flush({ categoryCount: 1, groupCount: 0, unassignedContainerCount: 0 });
      await settleGrid(fixture);

      expect(renderedText()).not.toContain('Not yet assigned');
    });

    it('clears every category on confirm, PUTting an empty set', async () => {
      vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(true) } as never);

      clearButtonFor('SRD').click();

      const put = httpTesting.expectOne('/api/v1/access/containers/bW9kLTE/categories');
      expect(put.request.body).toEqual({ categoryRefs: [] });
      put.flush({ categoryRefs: [] });
      await settle();

      httpTesting.expectOne('/api/v1/access/reconcile?scope=source&source=doors').flush({
        sources: [{ sourceId: 'doors', propagated: 0, retracted: 1, seeded: 0 }],
      });
      await settle();
      await new Promise((resolve) => setTimeout(resolve, 0));

      httpTesting
        .expectOne('/api/v1/access/containers?state=all')
        .flush({ containers: [{ ...CONTAINERS[0], categoryRefs: [] }, CONTAINERS[1]] });
      httpTesting
        .expectOne('/api/v1/access/summary')
        .flush({ categoryCount: 1, groupCount: 0, unassignedContainerCount: 1 });
      await settleGrid(fixture);

      expect(renderedText()).toContain('Not yet assigned');
    });

    it('does nothing when Clear is cancelled', async () => {
      vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(false) } as never);

      clearButtonFor('SRD').click();
      await settle();

      httpTesting.expectNone('/api/v1/access/containers/bW9kLTE/categories');
    });

    it('surfaces a failed save inline rather than losing it', async () => {
      vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(true) } as never);

      clearButtonFor('SRD').click();

      httpTesting.expectOne('/api/v1/access/containers/bW9kLTE/categories').flush(
        { type: 'about:blank', title: 'Not found', status: 404, detail: 'No object or container for this reference.' },
        { status: 404, statusText: 'Not Found' },
      );
      await settle();

      expect(renderedText()).toContain('No object or container for this reference.');
    });
  });
});
