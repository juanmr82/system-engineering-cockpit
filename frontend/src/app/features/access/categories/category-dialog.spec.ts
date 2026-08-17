import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { AuthStore } from '../../../core/auth/auth-store';
import { CategoryDialog, type CategoryDialogData } from './category-dialog';
import type { AccessCategory } from '../access.model';

/**
 * `AccessApiService.categories` guards its request on `AuthStore.hasRole()` (so a non-manager
 * never fires a wasted 403 the moment a screen injects the service) — a fake that always answers
 * `false` keeps that resource from ever requesting anything here, which is simpler than the real
 * `AuthStore`'s own `/auth/me` httpResource and its own request to flush.
 */
class FakeAuthStore {
  hasRole = (): boolean => false;
}

const EXISTING: AccessCategory = {
  ref: 'Y2F0LTE',
  key: 'doors-srd',
  name: 'SRD',
  description: 'The SRD module',
  everyGroup: false,
  objectCount: 3,
  groupCount: 1,
};

describe('CategoryDialog', () => {
  let fixture: ComponentFixture<CategoryDialog>;
  let httpTesting: HttpTestingController;
  let closed: boolean | undefined;

  const element = (): HTMLElement => fixture.nativeElement;
  const renderedText = (): string => element().textContent ?? '';

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

  const type = async (input: HTMLInputElement | HTMLTextAreaElement, value: string): Promise<void> => {
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle();
  };

  const saveButton = (): HTMLButtonElement => require<HTMLButtonElement>('mat-dialog-actions button:last-of-type');

  async function open(data: CategoryDialogData): Promise<void> {
    closed = undefined;
    await TestBed.configureTestingModule({
      imports: [CategoryDialog],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: { close: (result: boolean) => (closed = result) } },
        { provide: AuthStore, useValue: new FakeAuthStore() },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CategoryDialog);
    httpTesting = TestBed.inject(HttpTestingController);
    await settle();
  }

  afterEach(() => httpTesting.verify());

  describe('create mode', () => {
    beforeEach(() => open({ category: null }));

    it('titles itself "New category" and offers an editable key field', () => {
      expect(renderedText()).toContain('New category');
      expect(element().querySelector('.sec-field-readout')).toBeNull();
    });

    it('keeps Save disabled until both key and name are filled in', async () => {
      expect(saveButton().disabled).toBe(true);

      const inputs = Array.from(element().querySelectorAll<HTMLInputElement>('input[matInput]'));
      await type(inputs[0], 'doors-icd');
      expect(saveButton().disabled).toBe(true);

      await type(inputs[1], 'ICD');
      expect(saveButton().disabled).toBe(false);
    });

    it('posts the key, name, description and everyGroup, and closes true on success', async () => {
      const inputs = Array.from(element().querySelectorAll<HTMLInputElement>('input[matInput]'));
      await type(inputs[0], 'doors-icd');
      await type(inputs[1], 'ICD');
      await type(require<HTMLTextAreaElement>('textarea[matInput]'), 'The interface control document');

      saveButton().click();

      const request = httpTesting.expectOne('/api/v1/access/categories');
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({
        key: 'doors-icd',
        name: 'ICD',
        description: 'The interface control document',
        everyGroup: false,
      });

      request.flush({ ...EXISTING, ref: 'aWNk', key: 'doors-icd', name: 'ICD' });
      await settle();
      expect(closed).toBe(true);
    });

    // R7: a failed write leaves the dialog open with the input intact — there is no staging
    // layer to recover it from.
    it('keeps the dialog open with the error inline when the key is already in use', async () => {
      const inputs = Array.from(element().querySelectorAll<HTMLInputElement>('input[matInput]'));
      await type(inputs[0], 'doors-srd');
      await type(inputs[1], 'SRD again');

      saveButton().click();
      httpTesting.expectOne('/api/v1/access/categories').flush(
        {
          type: 'about:blank',
          title: 'Category key already in use',
          status: 409,
          detail: "A category with the key 'doors-srd' already exists. Choose a different key.",
        },
        { status: 409, statusText: 'Conflict' },
      );
      await settle();

      expect(closed).toBeUndefined();
      expect(renderedText()).toContain('already exists');
    });
  });

  describe('edit mode', () => {
    beforeEach(() => open({ category: EXISTING }));

    it('titles itself "Edit category", shows the key as read-only, and pre-fills the rest', () => {
      expect(renderedText()).toContain('Edit category');
      expect(renderedText()).toContain('doors-srd');
      expect(element().querySelectorAll('input[matInput]').length).toBe(1);

      const name = require<HTMLInputElement>('input[matInput]');
      expect(name.value).toBe('SRD');
      expect(require<HTMLTextAreaElement>('textarea[matInput]').value).toBe('The SRD module');
    });

    it('patches name, description and everyGroup, never key, and closes true on success', async () => {
      await type(require<HTMLInputElement>('input[matInput]'), 'SRD (renamed)');

      saveButton().click();

      const request = httpTesting.expectOne('/api/v1/access/categories/Y2F0LTE');
      expect(request.request.method).toBe('PATCH');
      expect(request.request.body).toEqual({
        name: 'SRD (renamed)',
        description: 'The SRD module',
        everyGroup: false,
      });
      expect((request.request.body as Record<string, unknown>)['key']).toBeUndefined();

      request.flush({ ...EXISTING, name: 'SRD (renamed)' });
      await settle();
      expect(closed).toBe(true);
    });
  });
});
