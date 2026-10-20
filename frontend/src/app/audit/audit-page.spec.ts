import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { errorInterceptor } from '../core/interceptors';
import { provideFakeAuth } from '../core/testing';
import { AuditEvent, AuditEventPage, AuditPage } from './audit-page';

function event(overrides: Partial<AuditEvent>): AuditEvent {
  return {
    id: 1,
    at: '2026-10-20T08:00:00Z',
    actor: 'bob',
    action: 'APPROVAL_APPROVED',
    targetType: 'run',
    targetId: 'r-1',
    details: 'create_ticket {"title":"Dock"}',
    ...overrides,
  };
}

function page(items: AuditEvent[], overrides: Partial<AuditEventPage> = {}): AuditEventPage {
  return { items, page: 0, size: 50, total: items.length, totalPages: 1, ...overrides };
}

/** Lets the page react and send the requests that follow (whenStable would wait for them). */
async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await Promise.resolve();
    TestBed.tick();
  }
}

describe('AuditPage', () => {
  let http: HttpTestingController;

  async function render(first: AuditEventPage) {
    TestBed.configureTestingModule({
      imports: [AuditPage],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        provideFakeAuth('admin', ['user', 'admin']),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(AuditPage);
    fixture.detectChanges();
    TestBed.tick();
    http.expectOne((req) => req.url === '/api/audit' && req.params.get('page') === '0').flush(first);
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  afterEach(() => http.verify());

  it('lists the entries with readable actions', async () => {
    const { element } = await render(page([event({}), event({ id: 2, action: 'SETTINGS_CHANGED', actor: 'admin', targetType: 'settings', targetId: null, details: 'temperature: 0.2 → 0.9' })]));

    const rows = Array.from(element.querySelectorAll('tbody tr')).map((tr) => tr.textContent ?? '');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toContain('Approval approved');
    expect(rows[1]).toContain('Settings changed');
    expect(rows[1]).toContain('temperature: 0.2 → 0.9');
  });

  it('sends the filters to the backend and starts again from the first page', async () => {
    const { fixture, element } = await render(page([event({})], { total: 120, totalPages: 3 }));

    element.querySelector<HTMLButtonElement>('.pager button:last-child')!.click();
    await settle();
    http.expectOne((req) => req.url === '/api/audit' && req.params.get('page') === '1').flush(page([], { page: 1, total: 120, totalPages: 3 }));
    await fixture.whenStable();

    const select = element.querySelector<HTMLSelectElement>('select')!;
    select.value = 'DOCUMENT_DELETED';
    select.dispatchEvent(new Event('change'));
    const actor = element.querySelector<HTMLInputElement>('input[type=text]')!;
    actor.value = ' admin ';
    actor.dispatchEvent(new Event('change'));
    await settle();

    const req = http.expectOne((r) => r.url === '/api/audit' && r.params.get('actor') === 'admin');
    expect(req.request.params.get('action')).toBe('DOCUMENT_DELETED');
    expect(req.request.params.get('page')).toBe('0');
    req.flush(page([]));
    await fixture.whenStable();
    expect(element.querySelector('tbody')?.textContent).toContain('No audit entries match');
  });

  it('downloads the filtered entries as CSV', async () => {
    const createObjectURL = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:audit');
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    try {
      const { fixture, element } = await render(page([event({})]));

      element.querySelector<HTMLButtonElement>('.page-header button')!.click();
      const req = http.expectOne((r) => r.url === '/api/audit/export');
      expect(req.request.responseType).toBe('blob');
      req.flush(new Blob(['at,actor\n'], { type: 'text/csv' }));
      await fixture.whenStable();

      expect(createObjectURL).toHaveBeenCalled();
      expect(click).toHaveBeenCalled();
    } finally {
      createObjectURL.mockRestore();
      revoke.mockRestore();
      click.mockRestore();
    }
  });
});
