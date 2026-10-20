import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { ChatStore } from '../core/chat-store';
import { Run, Session } from '../core/models';
import { ConversationList, SEARCH_DEBOUNCE_MS } from './conversation-list';

function session(id: string, title: string): Session {
  return { id, userId: 'alice', createdAt: '', title, lastActivityAt: '2026-10-20T08:00:00Z', runs: 2 };
}

describe('ConversationList', () => {
  let http: HttpTestingController;
  let opened: string[];
  const current = signal<Session | null>(null);

  async function render(sessions: Session[]) {
    opened = [];
    const store: Partial<ChatStore> = {
      session: current.asReadonly(),
      sending: signal(false).asReadonly(),
      lastRun: signal<Run | null>(null).asReadonly(),
      open: async (id: string) => {
        opened.push(id);
      },
      newConversation: async () => {
        opened.push('new');
      },
    };
    TestBed.configureTestingModule({
      imports: [ConversationList],
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting(), { provide: ChatStore, useValue: store }],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ConversationList);
    fixture.detectChanges();
    http.expectOne('/api/sessions?q=').flush(sessions);
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  afterEach(() => http.verify());

  it('lists the conversations, marks the current one and reopens another', async () => {
    current.set(session('s1', 'VPN keeps dropping'));
    const { element } = await render([session('s2', 'Printer offline'), session('s1', 'VPN keeps dropping')]);

    const titles = Array.from(element.querySelectorAll('.title')).map((e) => e.textContent?.trim());
    expect(titles).toEqual(['Printer offline', 'VPN keeps dropping']);
    expect(element.querySelector('li.current .title')?.textContent).toBe('VPN keeps dropping');

    element.querySelector<HTMLButtonElement>('li:not(.current) .open')!.click();
    expect(opened).toEqual(['s2']);
  });

  it('searches after a short pause instead of on every key', async () => {
    current.set(null);
    const { fixture, element } = await render([]);
    const input = element.querySelector<HTMLInputElement>('input[type=search]')!;

    input.value = 'pri';
    input.dispatchEvent(new Event('input'));
    input.value = 'printer';
    input.dispatchEvent(new Event('input'));
    await new Promise((resolve) => setTimeout(resolve, SEARCH_DEBOUNCE_MS + 50));
    TestBed.tick();

    http.expectOne('/api/sessions?q=printer').flush([session('s2', 'Printer offline')]);
    await fixture.whenStable();
    expect(element.querySelectorAll('.title')).toHaveLength(1);
  });

  it('renames a conversation and refreshes the list', async () => {
    current.set(null);
    const { fixture, element } = await render([session('s2', 'Printer offline')]);

    element.querySelector<HTMLButtonElement>('.edit')!.click();
    await fixture.whenStable();
    const input = element.querySelector<HTMLInputElement>('input.rename')!;
    input.value = 'Printer on floor 3';
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));

    const req = http.expectOne({ method: 'PATCH', url: '/api/sessions/s2' });
    expect(req.request.body).toEqual({ title: 'Printer on floor 3' });
    req.flush(session('s2', 'Printer on floor 3'));
    await Promise.resolve();
    TestBed.tick();
    http.expectOne('/api/sessions?q=').flush([session('s2', 'Printer on floor 3')]);
    await fixture.whenStable();
    expect(element.querySelector('.title')?.textContent).toBe('Printer on floor 3');
  });

  it('deletes the current conversation after confirmation and starts a new one', async () => {
    current.set(session('s1', 'VPN keeps dropping'));
    const { fixture, element } = await render([session('s1', 'VPN keeps dropping')]);
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    element.querySelector<HTMLButtonElement>('[aria-label="Delete VPN keeps dropping"]')!.click();
    http.expectOne({ method: 'DELETE', url: '/api/sessions/s1' }).flush(null, { status: 204, statusText: 'No Content' });
    await new Promise((resolve) => setTimeout(resolve, 0));
    TestBed.tick();

    expect(opened).toEqual(['new']);
    http.expectOne('/api/sessions?q=').flush([]);
    await fixture.whenStable();
    expect(element.querySelector('.title')).toBeNull();
  });
});
