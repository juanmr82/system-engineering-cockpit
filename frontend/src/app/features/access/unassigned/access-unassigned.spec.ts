import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { AgGridAngular } from 'ag-grid-angular';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { AuthStore } from '../../../core/auth/auth-store';
import { settleGrid } from '../../../core/grid/grid-testing';
import type { UnassignedContainer } from '../access.model';
import { AccessUnassigned } from './access-unassigned';

const CONTAINERS: UnassignedContainer[] = [
  { ref: 'bW9kLTE', sourceId: 'doors', name: 'SRD', invisibleItemCount: 12 },
  { ref: 'cHJvai0x', sourceId: 'jira', name: 'Avionics Board', invisibleItemCount: 3 },
];

class FakeAuthStore {
  isLoading = (): boolean => false;
  hasRole = (): boolean => true;
}

describe('AccessUnassigned', () => {
  let fixture: ComponentFixture<AccessUnassigned>;
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

  const settle = async (): Promise<void> => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };

  // Drives selection through the grid API rather than ag-grid's own internal checkbox DOM: this
  // is what ag-grid itself recommends for tests, and it isolates "does this view's own
  // selectionChanged wiring work" from "does ag-grid's checkbox implementation work" — the
  // second is ag-grid's own test suite's job, not this file's.
  const selectRow = async (ref: string): Promise<void> => {
    const grid = fixture.debugElement.query(By.directive(AgGridAngular)).componentInstance as AgGridAngular;
    const node = grid.api?.getRowNode(ref);
    node?.setSelected(true);
    // ag-grid's own selection state updates synchronously (getSelectedRows() above already
    // reflects it), but the selectionChanged *event* it dispatches does not reach this test's
    // Angular output binding until a macrotask later — the same class of timing gap
    // grid-testing.ts's own flushGridFrames() exists for on the render side.
    await new Promise((resolve) => setTimeout(resolve, 0));
  };

  const assignButton = (): HTMLButtonElement => require<HTMLButtonElement>('.sec-access-unassigned__bar button');

  async function setUp(containers = CONTAINERS): Promise<void> {
    fakeAuthStore = new FakeAuthStore();
    await TestBed.configureTestingModule({
      imports: [AccessUnassigned],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: AuthStore, useValue: fakeAuthStore },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AccessUnassigned);
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    httpTesting.expectOne('/api/v1/access/containers?state=unassigned').flush({ containers });
    // AccessApiService constructs categories/groups/defaults alongside unassignedContainers on
    // injection, and AccessBadgeService (also injected here, for the post-assign sidenav
    // refresh) fires its own summary request too — none of them are what this view reads
    // directly, but every stray request still has to be answered here for verify() not to see
    // it hanging.
    httpTesting.match('/api/v1/access/categories').forEach((request) => request.flush({ categories: [] }));
    httpTesting.match('/api/v1/access/groups').forEach((request) => request.flush({ groups: [] }));
    httpTesting.match('/api/v1/access/defaults').forEach((request) => request.flush({ defaults: [] }));
    httpTesting
      .match('/api/v1/access/containers?state=all')
      .forEach((request) => request.flush({ containers: [] }));
    httpTesting
      .match('/api/v1/access/summary')
      .forEach((request) => request.flush({ categoryCount: 0, groupCount: 0, unassignedContainerCount: containers.length }));
    await settleGrid(fixture);
  }

  afterEach(() => httpTesting.verify());

  it('refuses a caller without the Access manager role, without ever requesting the queue', async () => {
    fakeAuthStore = new FakeAuthStore();
    fakeAuthStore.hasRole = () => false;
    await TestBed.configureTestingModule({
      imports: [AccessUnassigned],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: AuthStore, useValue: fakeAuthStore },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AccessUnassigned);
    httpTesting = TestBed.inject(HttpTestingController);
    await settle();

    expect(renderedText()).toContain('Access manager role');
    httpTesting.expectNone('/api/v1/access/containers?state=unassigned');
  });

  it('shows the empty state when nothing is waiting', async () => {
    await setUp([]);
    expect(renderedText()).toContain('Nothing waiting');
  });

  describe('with containers queued', () => {
    beforeEach(() => setUp());

    it('lists every container with its source and invisible-item count', () => {
      expect(renderedText()).toContain('SRD');
      expect(renderedText()).toContain('DOORS');
      expect(renderedText()).toContain('12');
      expect(renderedText()).toContain('Avionics Board');
      expect(renderedText()).toContain('2 not assigned');
    });

    it('keeps Assign categories disabled until at least one row is selected', async () => {
      expect(assignButton().disabled).toBe(true);

      await selectRow('bW9kLTE');
      await settle();

      expect(assignButton().disabled).toBe(false);
      expect(assignButton().textContent).toContain('1');
    });

    // The confirmed design decision from planning: one gesture closes the loop, not two.
    it('assigns categories per container, reconciles every touched source, and reloads the queue', async () => {
      await selectRow('bW9kLTE');
      await selectRow('cHJvai0x');
      await settle();

      vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({
        afterClosed: () => of(['Y2F0LTE']),
      } as never);

      assignButton().click();
      await settle();

      const doorsPut = httpTesting.expectOne('/api/v1/access/containers/bW9kLTE/categories');
      expect(doorsPut.request.method).toBe('PUT');
      expect(doorsPut.request.body).toEqual({ categoryRefs: ['Y2F0LTE'] });
      doorsPut.flush({ categoryRefs: ['Y2F0LTE'] });

      const jiraPut = httpTesting.expectOne('/api/v1/access/containers/cHJvai0x/categories');
      expect(jiraPut.request.body).toEqual({ categoryRefs: ['Y2F0LTE'] });
      jiraPut.flush({ categoryRefs: ['Y2F0LTE'] });
      // The reconcile calls are only issued after both PUT promises resolve (Promise.all inside
      // assign()), which needs a microtask tick beyond the synchronous flush above.
      await settle();

      const doorsReconcile = httpTesting.expectOne('/api/v1/access/reconcile?scope=source&source=doors');
      expect(doorsReconcile.request.method).toBe('POST');
      doorsReconcile.flush({ sources: [{ sourceId: 'doors', propagated: 12, retracted: 0, seeded: 0 }] });

      const jiraReconcile = httpTesting.expectOne('/api/v1/access/reconcile?scope=source&source=jira');
      jiraReconcile.flush({ sources: [{ sourceId: 'jira', propagated: 3, retracted: 0, seeded: 0 }] });

      await settle();
      // reload() schedules the refetch rather than issuing it — the same trap every earlier
      // screen's specs found — so a macrotask tick is what actually pulls the resource's effect.
      await new Promise((resolve) => setTimeout(resolve, 0));

      httpTesting.expectOne('/api/v1/access/containers?state=unassigned').flush({ containers: [] });
      // The Containers screen (spec §10.2 screen 5) reads the same state through its own,
      // separately-cached resource — this reload keeps it from going stale after this write too.
      httpTesting.expectOne('/api/v1/access/containers?state=all').flush({ containers: [] });
      httpTesting.expectOne('/api/v1/access/summary').flush({ categoryCount: 1, groupCount: 0, unassignedContainerCount: 0 });
      await settleGrid(fixture);

      expect(renderedText()).toContain('Nothing waiting');
    });

    it('surfaces a failed assignment inline via the snackbar rather than losing it', async () => {
      await selectRow('bW9kLTE');
      await settle();

      vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({
        afterClosed: () => of(['Y2F0LTE']),
      } as never);

      assignButton().click();
      await settle();

      httpTesting.expectOne('/api/v1/access/containers/bW9kLTE/categories').flush(
        { type: 'about:blank', title: 'Not found', status: 404, detail: 'No object or container for this reference.' },
        { status: 404, statusText: 'Not Found' },
      );
      await settle();

      expect(document.body.textContent).toContain('No object or container for this reference.');
    });
  });
});
