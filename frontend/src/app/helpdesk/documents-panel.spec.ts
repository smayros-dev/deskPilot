import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { errorInterceptor } from '../core/interceptors';
import { KnowledgeDocument } from '../core/models';
import { Notifications } from '../core/notifications';
import { provideFakeAuth } from '../core/testing';
import { DOCUMENTS_POLL_MS, MAX_DOCUMENT_BYTES, documentProblem } from './documents-api';
import { DocumentsPanel } from './documents-panel';

function doc(overrides: Partial<KnowledgeDocument>): KnowledgeDocument {
  return {
    id: 1,
    title: 'Office hardware',
    fileName: 'hardware.md',
    format: 'MARKDOWN',
    sizeBytes: 2048,
    status: 'READY',
    chunks: 4,
    error: null,
    uploadedBy: 'admin',
    uploadedAt: '2026-10-20T08:00:00Z',
    indexedAt: '2026-10-20T08:00:05Z',
    ...overrides,
  };
}

function file(name: string, size = 100): File {
  return new File([new Uint8Array(size)], name);
}

/** Lets resources issue their requests (they start after a microtask and a change detection pass). */
async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await Promise.resolve();
    TestBed.tick();
  }
}

async function waitFor(condition: () => boolean, timeoutMs = 10_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!condition()) {
    if (Date.now() > deadline) {
      throw new Error('condition not met in time');
    }
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

describe('documentProblem', () => {
  it('accepts the supported types and rejects others, empty and too large files', () => {
    expect(documentProblem(file('guide.PDF'))).toBeNull();
    expect(documentProblem(file('notes.md'))).toBeNull();
    expect(documentProblem(file('page.htm'))).toBeNull();
    expect(documentProblem(file('setup.exe'))).toContain('not supported');
    expect(documentProblem(file('README'))).toContain('not supported');
    expect(documentProblem(file('empty.txt', 0))).toContain('empty');
    expect(documentProblem(file('huge.pdf', MAX_DOCUMENT_BYTES + 1))).toContain('10 MB');
  });
});

describe('DocumentsPanel', () => {
  let http: HttpTestingController;

  async function render(documents: KnowledgeDocument[], pollMs = 60_000) {
    TestBed.configureTestingModule({
      imports: [DocumentsPanel],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        provideFakeAuth('admin', ['user', 'admin']),
        { provide: DOCUMENTS_POLL_MS, useValue: pollMs },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(DocumentsPanel);
    fixture.detectChanges();
    await settle();
    http.expectOne('/api/knowledge-base/documents').flush(documents);
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  afterEach(() => http.verify());

  it('lists documents with their status, articles and errors', async () => {
    const { element } = await render([doc({}), doc({ id: 2, title: 'Scan', status: 'FAILED', chunks: 0, error: 'No text found' })]);

    const rows = Array.from(element.querySelectorAll('tbody tr')).map((row) => row.textContent ?? '');
    expect(rows[0]).toContain('Office hardware');
    expect(rows[0]).toContain('ready');
    expect(rows[1]).toContain('failed');
    expect(rows[1]).toContain('No text found');
  });

  it('does not upload an unsupported file and explains why', async () => {
    const { fixture, element } = await render([]);

    await fixture.componentInstance.upload(file('setup.exe'));
    await fixture.whenStable();

    http.expectNone('/api/knowledge-base/documents');
    expect(element.querySelector('[role=alert]')?.textContent).toContain('not supported');
  });

  it('uploads the file with its title as multipart form data, then refreshes the list', async () => {
    const { fixture } = await render([]);
    (fixture.componentInstance as unknown as { title: { set(v: string): void } }).title.set('Office hardware');

    const uploading = fixture.componentInstance.upload(file('hardware.md'));
    const req = http.expectOne({ method: 'POST', url: '/api/knowledge-base/documents' });
    const body = req.request.body as FormData;
    expect((body.get('file') as File).name).toBe('hardware.md');
    expect(body.get('title')).toBe('Office hardware');
    req.flush(doc({ status: 'PROCESSING', chunks: 0 }), { status: 202, statusText: 'Accepted' });
    await uploading;
    await settle();

    http.expectOne('/api/knowledge-base/documents').flush([doc({ status: 'PROCESSING', chunks: 0 })]);
    expect(TestBed.inject(Notifications).toasts()[0].message).toContain('being split into articles');
  });

  it('deletes a document after confirmation', async () => {
    const { fixture, element } = await render([doc({})]);
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    let changed = 0;
    fixture.componentInstance.changed.subscribe(() => changed++);

    Array.from(element.querySelectorAll<HTMLButtonElement>('.actions button')).find((b) => b.textContent?.includes('Delete'))!.click();
    expect(confirmSpy).toHaveBeenCalled();
    http.expectOne({ method: 'DELETE', url: '/api/knowledge-base/documents/1' }).flush(null, { status: 204, statusText: 'No Content' });
    await waitFor(() => http.match('/api/knowledge-base/documents').length > 0 || changed > 0);

    expect(changed).toBe(1);
    confirmSpy.mockRestore();
  });

  it('refreshes while a document is processing and reports when it is done', async () => {
    const { fixture } = await render([doc({ status: 'PROCESSING', chunks: 0 })], 20);
    let changed = 0;
    fixture.componentInstance.changed.subscribe(() => changed++);

    // match() takes the requests out of the queue, so keep the ones it finds.
    let reload: TestRequest[] = [];
    await waitFor(() => (reload = http.match('/api/knowledge-base/documents')).length > 0);
    reload.forEach((req) => req.flush([doc({})]));
    await waitFor(() => changed > 0);
    http.match('/api/knowledge-base/documents').forEach((req) => req.flush([doc({})]));

    await new Promise((resolve) => setTimeout(resolve, 100));
    http.expectNone('/api/knowledge-base/documents');
  }, 20_000);
});
