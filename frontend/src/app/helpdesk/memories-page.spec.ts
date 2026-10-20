import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ChatStore } from '../core/chat-store';
import { errorInterceptor } from '../core/interceptors';
import { Memory } from '../core/models';
import { Notifications } from '../core/notifications';
import { provideFakeAuth, provideFakeEvents } from '../core/testing';
import { MemoriesPage } from './memories-page';

const MEMORIES: Memory[] = [
  { id: 1, content: 'Works on a MacBook', createdAt: '2026-10-20T08:00:00Z' },
  { id: 2, content: 'Prefers e-mail', createdAt: '2026-10-19T08:00:00Z' },
];

/** Lets the page react to a response and send the requests that follow (whenStable would wait for them). */
async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await Promise.resolve();
    TestBed.tick();
  }
}

describe('MemoriesPage', () => {
  let http: HttpTestingController;

  async function render() {
    TestBed.configureTestingModule({
      imports: [MemoriesPage],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        provideFakeAuth('alice'),
        provideFakeEvents(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(MemoriesPage);
    fixture.detectChanges();
    http.expectOne('/api/me/memories').flush(MEMORIES);
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
  });

  it('forgets one memory and reloads the list', async () => {
    const { fixture, element } = await render();

    element.querySelector<HTMLButtonElement>('button[aria-label="Forget: Works on a MacBook"]')!.click();
    http.expectOne({ method: 'DELETE', url: '/api/me/memories/1' }).flush(null);
    await settle();
    http.expectOne('/api/me/memories').flush([MEMORIES[1]]);
    await fixture.whenStable();

    expect(element.querySelectorAll('.memories li')).toHaveLength(1);
    expect(TestBed.inject(Notifications).toasts().at(-1)?.message).toBe('Forgotten.');
  });

  it('asks before forgetting everything', async () => {
    const { fixture, element } = await render();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);

    element.querySelector<HTMLButtonElement>('.page-header .button.danger')!.click();
    await fixture.whenStable();
    http.expectNone({ method: 'DELETE', url: '/api/me/memories' });

    confirm.mockReturnValue(true);
    element.querySelector<HTMLButtonElement>('.page-header .button.danger')!.click();
    http.expectOne({ method: 'DELETE', url: '/api/me/memories' }).flush(null);
    await settle();
    http.expectOne('/api/me/memories').flush([]);
    await fixture.whenStable();
    expect(element.querySelector('.memories')?.textContent).toContain('Nothing remembered yet');
  });

  it('deletes all my data, then starts a new conversation', async () => {
    const { fixture, element } = await render();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const newConversation = vi.spyOn(TestBed.inject(ChatStore), 'newConversation').mockResolvedValue();

    Array.from(element.querySelectorAll<HTMLButtonElement>('.privacy button'))
      .find((b) => b.textContent?.includes('Delete my conversations'))!.click();
    http.expectOne({ method: 'DELETE', url: '/api/me/data' }).flush(null);
    await settle();
    http.expectOne('/api/me/memories').flush([]);
    await fixture.whenStable();

    expect(newConversation).toHaveBeenCalled();
  });

  it('keeps the data when the backend refuses (a request is still running)', async () => {
    const { fixture, element } = await render();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const newConversation = vi.spyOn(TestBed.inject(ChatStore), 'newConversation').mockResolvedValue();

    Array.from(element.querySelectorAll<HTMLButtonElement>('.privacy button'))
      .find((b) => b.textContent?.includes('Delete my conversations'))!.click();
    http.expectOne({ method: 'DELETE', url: '/api/me/data' })
      .flush({ title: 'Conflict', detail: 'has a request in progress' }, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();

    expect(newConversation).not.toHaveBeenCalled();
    expect(TestBed.inject(Notifications).toasts().at(-1)?.message).toContain('in progress');
  });

  it('downloads my data as JSON', async () => {
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:data');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const { fixture, element } = await render();

    Array.from(element.querySelectorAll<HTMLButtonElement>('.privacy button'))
      .find((b) => b.textContent?.includes('Download'))!.click();
    const req = http.expectOne('/api/me/export');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['{}'], { type: 'application/json' }));
    await fixture.whenStable();

    expect(click).toHaveBeenCalled();
  });
});
