import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { JiraIntegration } from './jira-integration';
import type { JiraConnection, JiraProjectList } from '../../jira/jira.model';

const CONNECTED: JiraConnection = {
  configured: true,
  host: 'https://jira.example.com',
  platform: 'datacenter',
};

const PROJECTS: JiraProjectList = {
  projects: [
    {
      ref: 'amlyYTpwcm9qZWN0OlBST0o',
      key: 'PROJ',
      name: 'Avionics platform',
      projectType: 'software',
      inScope: true,
      enabled: true,
      jql: 'status != Done',
      issueCount: 128,
    },
    // In the graph but never added to the scope: it is offered, not listed as imported.
    {
      ref: 'amlyYTpwcm9qZWN0Ok9MRA',
      key: 'OLD',
      name: 'Retired project',
      projectType: 'software',
      inScope: false,
      enabled: false,
      jql: '',
      issueCount: 0,
    },
  ],
  available: [{ key: 'NEW', name: 'New programme' }],
};

describe('JiraIntegration', () => {
  let fixture: ComponentFixture<JiraIntegration>;
  let httpTesting: HttpTestingController;

  const renderedText = (): string => fixture.nativeElement.textContent;

  const settle = async (): Promise<void> => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };

  /**
   * Renders after a write, without `whenStable()`.
   *
   * Two things collide here. Every write is `await`ed inside an async handler, so the `catch` that
   * sets the error message runs a microtask *after* the response is flushed — one render pass is
   * one step behind the component. But `whenStable()` **never resolves while an `httpResource` is
   * in flight**: it times the spec out rather than failing it, which is what a second `settle()`
   * did here. Draining the microtask queue by hand does the first without triggering the second.
   */
  const renderAfterWrite = async (): Promise<void> => {
    await Promise.resolve();
    await Promise.resolve();
    fixture.detectChanges();
  };

  /**
   * Answers whatever the service has in flight.
   *
   * `JiraApiService` is a root singleton holding three resources, so mounting this tab also starts
   * the issues request it does not render. Matching rather than asserting one request keeps the
   * spec about this component instead of about the service's fan-out.
   */
  const answer = async (connection: JiraConnection, projects?: JiraProjectList) => {
    httpTesting
      .match((request) => request.url === '/api/v1/jira/connection')
      .forEach((request) => request.flush(connection));
    httpTesting
      .match((request) => request.url.startsWith('/api/v1/jira/issues'))
      .forEach((request) =>
        request.flush({ columns: [], rows: [], total: 0, offset: 0, limit: 100 }),
      );
    // Always answered, even when this test does not care: the service starts it regardless of
    // whether JIRA is configured, and an unanswered request fails verify() in afterEach — which
    // then cascades into every test after it.
    httpTesting
      .match((request) => request.url === '/api/v1/jira/projects')
      .forEach((request) => request.flush(projects ?? { projects: [], available: [] }));
    await settle();
  };

  /** Flushes the reloads the component asked for after a successful write. */
  const flushReloads = async (projects: JiraProjectList): Promise<void> => {
    httpTesting
      .match((request) => request.url === '/api/v1/jira/projects' && request.method === 'GET')
      .forEach((request) => request.flush(projects));
    httpTesting
      .match((request) => request.url.startsWith('/api/v1/jira/issues'))
      .forEach((request) =>
        request.flush({ columns: [], rows: [], total: 0, offset: 0, limit: 100 }),
      );
    await settle();
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [JiraIntegration],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    }).compileComponents();

    fixture = TestBed.createComponent(JiraIntegration);
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('says where the fix is when JIRA is not configured', async () => {
    await answer({ configured: false, host: '', platform: 'datacenter' });

    // Host and token are backend configuration and are deliberately not editable here (§9), so an
    // editable form would be a control that writes nowhere.
    expect(renderedText()).toContain('Not connected');
    expect(renderedText()).toContain('backend configuration');
    expect(fixture.nativeElement.querySelector('input[matInput]')).toBeNull();
  });

  it('states that nothing is ever written back to JIRA', async () => {
    await answer(CONNECTED, PROJECTS);
    // The one guarantee an admin cannot verify from the outside, so the view makes it.
    expect(renderedText()).toContain('only reads from JIRA');
  });

  it('lists only the projects that are actually in scope', async () => {
    await answer(CONNECTED, PROJECTS);

    expect(renderedText()).toContain('Avionics platform');
    // OLD is a known project that nobody added, so it belongs in the picker, not in the table.
    expect(fixture.nativeElement.querySelector('[aria-label="Import OLD"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[aria-label="Import PROJ"]')).not.toBeNull();
  });

  it('commits a scope change on its own, with no Save button anywhere', async () => {
    await answer(CONNECTED, PROJECTS);

    // R7: one gesture, one request, one transaction. There is no staging layer to press.
    expect(fixture.nativeElement.textContent).not.toContain('Save changes');

    const toggle: HTMLElement = fixture.nativeElement.querySelector(
      '[aria-label="Import PROJ"]',
    );
    toggle.click();
    await renderAfterWrite();

    const saved = httpTesting.expectOne(
      (r) => r.url === '/api/v1/jira/projects' && r.method === 'POST',
    );
    // The JQL clause travels with the toggle: a partial body would clear it as a side effect of
    // pausing the project.
    expect(saved.request.body).toEqual({ key: 'PROJ', enabled: false, jql: 'status != Done' });
    saved.flush(PROJECTS);
    await settle();

    // The list that comes back is the server's, not a locally patched copy.
    await flushReloads(PROJECTS);
  });

  it('will not import when nothing is in scope', async () => {
    await answer(CONNECTED, { projects: [], available: [] });

    const importButton: HTMLButtonElement = Array.from(
      fixture.nativeElement.querySelectorAll('button'),
    ).find((b) => (b as HTMLElement).textContent?.includes('Import issues')) as HTMLButtonElement;

    expect(importButton.disabled).toBe(true);
    expect(renderedText()).toContain('No projects are being imported yet');
  });

  it('shows the server sentence when a scope change is refused', async () => {
    await answer(CONNECTED, PROJECTS);

    const toggle: HTMLElement = fixture.nativeElement.querySelector(
      '[aria-label="Import PROJ"]',
    );
    toggle.click();
    await renderAfterWrite();

    httpTesting
      .expectOne((r) => r.url === '/api/v1/jira/projects' && r.method === 'POST')
      .flush(
        {
          title: 'Project not found',
          detail: "JIRA has no project 'PROJ', or this account cannot browse it.",
        },
        { status: 404, statusText: 'Not Found' },
      );
    await renderAfterWrite();

    expect(renderedText()).toContain('cannot browse it');
  });
});
