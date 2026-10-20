import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { errorInterceptor } from '../core/interceptors';
import { provideFakeAuth } from '../core/testing';
import { KnowledgeGap } from './knowledge-gaps-api';
import { KnowledgeGapsPanel } from './knowledge-gaps-panel';

function gap(overrides: Partial<KnowledgeGap>): KnowledgeGap {
  return {
    id: 7,
    title: 'Jabra headset pairing with Teams fails',
    status: 'OPEN',
    questionCount: 3,
    reasons: { ESCALATED: 2, NOT_FOUND: 1 },
    questions: [
      { runId: 'r1', question: 'Jabra headset pairing with Teams fails', reason: 'ESCALATED', askedAt: '2026-10-19T09:00:00Z' },
      { runId: 'r2', question: 'Teams does not see my Jabra headset', reason: 'NOT_FOUND', askedAt: '2026-10-18T09:00:00Z' },
    ],
    draftTitle: null,
    draftContent: null,
    draftTags: null,
    draftedBy: null,
    articleId: null,
    detectedAt: '2026-10-19T10:00:00Z',
    updatedAt: '2026-10-19T10:00:00Z',
    decidedBy: null,
    decidedAt: null,
    ...overrides,
  };
}

const DRAFTED = gap({
  status: 'DRAFTED',
  draftTitle: 'Pair a Jabra headset with Teams',
  draftContent: '1. Open Teams settings.',
  draftTags: 'jabra,teams',
  draftedBy: 'qwen3:8b',
});

/** Lets the panel finish an action and issue the reload of the list. */
async function settle(): Promise<void> {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve();
    TestBed.tick();
  }
}

describe('KnowledgeGapsPanel', () => {
  let http: HttpTestingController;

  async function render(gaps: KnowledgeGap[]) {
    TestBed.configureTestingModule({
      imports: [KnowledgeGapsPanel],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        provideFakeAuth('bob', ['user', 'reviewer']),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(KnowledgeGapsPanel);
    fixture.detectChanges();
    http.expectOne('/api/knowledge-gaps').flush(gaps);
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  function button(element: HTMLElement, text: string): HTMLButtonElement {
    return Array.from(element.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.trim() === text)!;
  }

  afterEach(() => http.verify());

  it('lists pending gaps with their reasons and hides decided ones until asked', async () => {
    const { fixture, element } = await render([gap({}), gap({ id: 8, title: 'Canteen', status: 'DISMISSED', decidedBy: 'bob' })]);

    expect(element.querySelectorAll('article.gap').length).toBe(1);
    expect(element.querySelector('h3')?.textContent).toContain('Jabra headset');
    expect(element.querySelector('.count')?.textContent).toContain('3 questions');
    expect(Array.from(element.querySelectorAll('.reasons li')).map((li) => li.textContent?.trim())).toEqual(['escalated × 2', 'nothing found × 1']);

    element.querySelector<HTMLInputElement>('.toggle input')!.click();
    await fixture.whenStable();
    expect(element.querySelectorAll('article.gap').length).toBe(2);
  });

  it('asks the model for a draft and shows it for review', async () => {
    const { fixture, element } = await render([gap({})]);

    button(element, 'Draft with the model').click();
    await fixture.whenStable();
    expect(element.querySelector('[role=status]')?.textContent).toContain('writing a draft');
    http.expectOne({ method: 'POST', url: '/api/knowledge-gaps/7/draft' }).flush(DRAFTED);
    await settle();
    http.expectOne('/api/knowledge-gaps').flush([DRAFTED]);
    await fixture.whenStable();

    expect(element.querySelector('.draft h4')?.textContent).toContain('Pair a Jabra headset with Teams');
    expect(element.querySelector('.draft .notice')?.textContent).toContain('qwen3:8b');
  });

  it('saves an edited draft, then publishes it after confirmation', async () => {
    const { fixture, element } = await render([DRAFTED]);
    const published = vi.fn();
    fixture.componentInstance.published.subscribe(published);

    button(element, 'Edit').click();
    await fixture.whenStable();
    const content = element.querySelector<HTMLTextAreaElement>('textarea')!;
    content.value = '1. Open Teams settings.\n2. Pick the headset.';
    content.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    button(element, 'Save draft').click();
    const save = http.expectOne({ method: 'PUT', url: '/api/knowledge-gaps/7/draft' });
    expect(save.request.body).toEqual({ title: 'Pair a Jabra headset with Teams', content: '1. Open Teams settings.\n2. Pick the headset.', tags: 'jabra,teams' });
    save.flush({ ...DRAFTED, draftContent: save.request.body.content });
    await settle();
    http.expectOne('/api/knowledge-gaps').flush([{ ...DRAFTED, draftContent: save.request.body.content }]);
    await fixture.whenStable();

    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    button(element, 'Publish to the knowledge base').click();
    http.expectOne({ method: 'POST', url: '/api/knowledge-gaps/7/publish' })
      .flush({ ...DRAFTED, status: 'PUBLISHED', articleId: 42, decidedBy: 'bob' });
    await settle();
    http.expectOne('/api/knowledge-gaps').flush([]);
    await fixture.whenStable();

    expect(confirmSpy).toHaveBeenCalled();
    expect(published).toHaveBeenCalled();
    confirmSpy.mockRestore();
  });
});
