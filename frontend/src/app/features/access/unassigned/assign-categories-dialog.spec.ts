import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { AuthStore } from '../../../core/auth/auth-store';
import type { AccessCategoryListResponse } from '../access.model';
import { AssignCategoriesDialog, type AssignCategoriesDialogData } from './assign-categories-dialog';

const CATEGORIES: AccessCategoryListResponse = {
  categories: [
    { ref: 'Y2F0LTE', key: 'doors-srd', name: 'SRD', description: '', everyGroup: false, objectCount: 0, groupCount: 0 },
    { ref: 'Y2F0LTI', key: 'doors-icd', name: 'ICD', description: '', everyGroup: false, objectCount: 0, groupCount: 0 },
  ],
};

class FakeAuthStore {
  hasRole = (): boolean => true;
}

describe('AssignCategoriesDialog', () => {
  let fixture: ComponentFixture<AssignCategoriesDialog>;
  let httpTesting: HttpTestingController;
  let closed: string[] | undefined;

  const element = (): HTMLElement => fixture.nativeElement;

  const require = <T extends HTMLElement>(selector: string): T => {
    const found = element().querySelector<T>(selector);
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

  const confirmButton = (): HTMLButtonElement =>
    require<HTMLButtonElement>('mat-dialog-actions button:last-of-type');

  async function open(data: AssignCategoriesDialogData, categories = CATEGORIES): Promise<void> {
    closed = undefined;
    await TestBed.configureTestingModule({
      imports: [AssignCategoriesDialog],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: { close: (result: string[] | undefined) => (closed = result) } },
        { provide: AuthStore, useValue: new FakeAuthStore() },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AssignCategoriesDialog);
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    httpTesting.expectOne('/api/v1/access/categories').flush(categories);
    // AccessApiService constructs groups/unassignedContainers/defaults alongside categories on
    // injection; this dialog reads none of them, so the stray requests have to be flushed here
    // or verify() sees them hanging.
    httpTesting.match('/api/v1/access/groups').forEach((request) => request.flush({ groups: [] }));
    httpTesting
      .match('/api/v1/access/containers?state=unassigned')
      .forEach((request) => request.flush({ containers: [] }));
    httpTesting.match('/api/v1/access/defaults').forEach((request) => request.flush({ defaults: [] }));

    await settle();
  }

  afterEach(() => httpTesting.verify());

  it('names how many containers this will apply to', async () => {
    await open({ containerCount: 3 });
    expect(element().textContent).toContain('Assign categories to 3 containers');
  });

  it('lists every category as a checkbox, and keeps Assign disabled until one is picked', async () => {
    await open({ containerCount: 1 });

    expect(element().textContent).toContain('SRD');
    expect(element().textContent).toContain('ICD');
    expect(confirmButton().disabled).toBe(true);

    require<HTMLInputElement>('mat-checkbox input').click();
    await settle();

    expect(confirmButton().disabled).toBe(false);
  });

  it('closes with the selected category refs on confirm', async () => {
    await open({ containerCount: 1 });

    const checkboxes = Array.from(element().querySelectorAll<HTMLInputElement>('mat-checkbox input'));
    checkboxes[1].click();
    await settle();

    confirmButton().click();

    expect(closed).toEqual(['Y2F0LTI']);
  });

  it('closes with undefined on cancel', async () => {
    await open({ containerCount: 1 });

    require<HTMLButtonElement>('mat-dialog-actions button:first-of-type').click();

    expect(closed).toBeUndefined();
  });

  it('says to create a category first when there are none yet', async () => {
    await open({ containerCount: 1 }, { categories: [] });

    expect(element().textContent).toContain('create one on the Categories screen first');
  });
});
