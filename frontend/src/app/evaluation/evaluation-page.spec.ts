import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { errorInterceptor } from '../core/interceptors';
import { ModelInfo } from '../core/models';
import { provideFakeAuth } from '../core/testing';
import { EVALUATION_POLL_MS, EvaluationPage } from './evaluation-page';
import { Evaluation, Scenario } from './evaluation-api';

const SCENARIOS: Scenario[] = [
  scenario('vpn-drops', 'knowledge'),
  scenario('printer-add', 'knowledge'),
  scenario('lost-phone', 'ticket'),
];

const MODELS: ModelInfo[] = [
  { name: 'qwen3:8b', sizeBytes: 5e9, parameterSize: '8.2B', family: 'qwen3', capabilities: ['tools'], supportsTools: true, supportsThinking: true, chatModel: true },
  { name: 'gemma:2b', sizeBytes: 2e9, parameterSize: '2B', family: 'gemma', capabilities: [], supportsTools: false, supportsThinking: false, chatModel: true },
];

function scenario(id: string, category: string): Scenario {
  return {
    id,
    category,
    question: `${id}?`,
    expect: { outcome: 'ANSWERED', tools: [], forbiddenTools: [], contains: [], notContains: [], approvalContains: [] },
  };
}

function evaluation(overrides: Partial<Evaluation>): Evaluation {
  return {
    id: 'e1',
    model: 'qwen3:8b',
    status: 'COMPLETED',
    scenarioCount: 3,
    completed: 3,
    passed: 2,
    passRate: 2 / 3,
    avgLatencyMillis: 30_000,
    p95LatencyMillis: 50_000,
    categories: [
      { category: 'knowledge', scenarios: 2, passed: 2 },
      { category: 'ticket', scenarios: 1, passed: 0 },
    ],
    startedBy: 'admin',
    startedAt: '2026-10-19T10:00:00Z',
    finishedAt: '2026-10-19T10:02:00Z',
    error: null,
    results: [],
    ...overrides,
  };
}

/** Waits (real time) until the condition holds; the poll interval is shortened in the test that uses it. */
async function waitFor(condition: () => boolean, timeoutMs = 2000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!condition()) {
    if (Date.now() > deadline) {
      throw new Error('condition not met in time');
    }
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

/** Lets resources pick up responses and issue the requests that depend on them, without waiting for stability. */
async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await Promise.resolve();
    TestBed.tick();
  }
}

describe('EvaluationPage', () => {
  let http: HttpTestingController;

  async function render(evaluations: Evaluation[], detail: Evaluation, pollMs = 60_000) {
    TestBed.configureTestingModule({
      imports: [EvaluationPage],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        provideFakeAuth('admin', ['user', 'admin']),
        { provide: EVALUATION_POLL_MS, useValue: pollMs },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(EvaluationPage);
    fixture.detectChanges();
    http.expectOne('/api/evaluations/scenarios').flush(SCENARIOS);
    http.expectOne('/api/models').flush(MODELS);
    http.expectOne('/api/evaluations').flush(evaluations);
    // The selected evaluation (the newest) is requested once the list has arrived.
    await settle();
    http.expectOne(`/api/evaluations/${detail.id}`).flush(detail);
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  afterEach(() => http.verify());

  it('compares evaluations per category and shows why scenarios failed', async () => {
    const detail = evaluation({
      results: [
        { scenarioId: 'vpn-drops', category: 'knowledge', question: 'VPN?', passed: true, expectedOutcome: 'ANSWERED', outcome: 'ANSWERED', tools: ['search_knowledge_base'], failures: [], answer: 'Use IPSec', runId: 'r1', latencyMillis: 20_000, iterations: 2, tokens: 900 },
        { scenarioId: 'lost-phone', category: 'ticket', question: 'Phone?', passed: false, expectedOutcome: 'APPROVAL', outcome: 'ANSWERED', tools: [], failures: ['expected outcome APPROVAL, got ANSWERED'], answer: 'Call IT', runId: 'r2', latencyMillis: 40_000, iterations: 1, tokens: 400 },
      ],
    });
    const { fixture, element } = await render([evaluation({}), evaluation({ id: 'e0', model: 'gemma4:26b', passRate: 1, passed: 3 })], detail);

    const header = Array.from(element.querySelectorAll('.compare th')).map((th) => th.textContent?.trim());
    expect(header).toContain('knowledge');
    expect(header).toContain('ticket');
    const firstRow = element.querySelector('.compare tbody tr')!.textContent!;
    expect(firstRow).toContain('qwen3:8b');
    expect(firstRow).toContain('67%');
    expect(firstRow).toContain('0/1');

    expect(element.querySelectorAll('.results > li')).toHaveLength(2);
    expect(element.querySelector('.results li.failed .failures')?.textContent).toContain('expected outcome APPROVAL');

    element.querySelector<HTMLInputElement>('.toggle input')!.click();
    await fixture.whenStable();
    expect(element.querySelectorAll('.results > li')).toHaveLength(1);
  });

  it('only offers tool-capable models and starts the selected categories', async () => {
    const { fixture, element } = await render([evaluation({})], evaluation({}));

    const options = Array.from(element.querySelectorAll<HTMLOptionElement>('.start-form option')).map((o) => o.value);
    expect(options).toEqual(['qwen3:8b']);

    Array.from(element.querySelectorAll<HTMLButtonElement>('.categories button')).find((b) => b.textContent?.trim() === 'ticket')!.click();
    await fixture.whenStable();
    const start = element.querySelector<HTMLButtonElement>('.start-form .button.primary')!;
    expect(start.textContent).toContain('Run 1 scenarios');

    start.click();
    const req = http.expectOne({ method: 'POST', url: '/api/evaluations' });
    expect(req.request.body).toEqual({ model: 'qwen3:8b', categories: ['ticket'] });
    req.flush(evaluation({ id: 'e2', status: 'RUNNING', completed: 0, passed: 0, scenarioCount: 1, categories: [] }));
    await settle();
    http.expectOne('/api/evaluations').flush([evaluation({ id: 'e2', status: 'RUNNING', completed: 0, scenarioCount: 1 })]);
    http.expectOne('/api/evaluations/e2').flush(evaluation({ id: 'e2', status: 'RUNNING', completed: 0, scenarioCount: 1 }));
    await fixture.whenStable();

    expect(element.querySelector('.progress-card')?.textContent).toContain('0 / 1 scenarios');
    expect(start.disabled).toBe(true);
  });

  it('refreshes while an evaluation is running, then stops', async () => {
    const running = evaluation({ status: 'RUNNING', completed: 1, passed: 1 });
    const { fixture } = await render([running], running, 20);

    // match() takes the requests out of the queue, so keep the one it finds.
    let reload: TestRequest[] = [];
    await waitFor(() => (reload = http.match('/api/evaluations')).length > 0);
    reload[0].flush([evaluation({})]);
    http.expectOne('/api/evaluations/e1').flush(evaluation({}));
    await fixture.whenStable();

    await new Promise((resolve) => setTimeout(resolve, 100));
    http.expectNone('/api/evaluations');
  });
});
