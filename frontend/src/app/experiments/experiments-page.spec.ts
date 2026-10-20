import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { Experiment, PromptVersion, VariantResult } from '../core/experiments';
import { errorInterceptor } from '../core/interceptors';
import { ModelInfo } from '../core/models';
import { provideFakeAuth } from '../core/testing';
import { ExperimentsPage } from './experiments-page';

const MODELS: ModelInfo[] = [
  { name: 'qwen3:8b', sizeBytes: 5e9, parameterSize: '8.2B', family: 'qwen3', capabilities: ['tools'], supportsTools: true, supportsThinking: true, supportsVision: false, chatModel: true },
  { name: 'llama3.2:3b', sizeBytes: 2e9, parameterSize: '3B', family: 'llama', capabilities: ['tools'], supportsTools: true, supportsThinking: false, supportsVision: false, chatModel: true },
  { name: 'gemma:2b', sizeBytes: 1.7e9, parameterSize: '2B', family: 'gemma', capabilities: ['completion'], supportsTools: false, supportsThinking: false, supportsVision: false, chatModel: true },
];

const PROMPTS: PromptVersion[] = [
  { id: 7, name: 'concise-v2', content: 'Answer in three sentences.', createdBy: 'admin', createdAt: '2026-10-20T09:00:00Z' },
];

function variant(overrides: Partial<VariantResult>): VariantResult {
  return {
    variant: 'A',
    provider: 'ollama',
    model: 'qwen3:8b',
    promptVersionId: null,
    promptVersionName: null,
    runs: 10,
    finished: 10,
    successRate: 0.7,
    escalationRate: 0.1,
    avgLatencyMillis: 42000,
    p95LatencyMillis: 80000,
    avgTokens: 3000,
    up: 2,
    down: 1,
    satisfactionRate: 0.667,
    ...overrides,
  };
}

function experiment(overrides: Partial<Experiment>): Experiment {
  return {
    id: 'e1',
    name: 'llama vs qwen',
    status: 'RUNNING',
    bPercent: 30,
    startedBy: 'admin',
    startedAt: '2026-10-20T10:00:00Z',
    stoppedBy: null,
    stoppedAt: null,
    variants: [
      variant({}),
      variant({ variant: 'B', model: 'llama3.2:3b', promptVersionId: 7, promptVersionName: 'concise-v2', successRate: 0.9, runs: 4, finished: 4 }),
    ],
    ...overrides,
  };
}

/** Lets signals and resources react (e.g. a reload after a save) without waiting for pending requests. */
async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await Promise.resolve();
    TestBed.tick();
  }
}

describe('ExperimentsPage', () => {
  let http: HttpTestingController;

  async function render(experiments: Experiment[]) {
    TestBed.configureTestingModule({
      imports: [ExperimentsPage],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        provideFakeAuth('admin', ['user', 'admin']),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ExperimentsPage);
    fixture.detectChanges();
    http.expectOne('/api/experiments').flush(experiments);
    http.expectOne('/api/prompts').flush(PROMPTS);
    http.expectOne('/api/models').flush(MODELS);
    http.expectOne('/api/providers').flush([]);
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  afterEach(() => http.verify());

  it('compares the variants of the running experiment and marks the leader', async () => {
    const { element } = await render([experiment({})]);

    const running = element.querySelector('.running')!;
    expect(running.textContent).toContain('llama vs qwen');
    expect(running.textContent).toContain('A 70% · B 30%');
    const rows = Array.from(running.querySelectorAll('tbody tr'));
    expect(rows.map((r) => r.querySelector('td')!.textContent!.trim())).toEqual(['qwen3:8b', 'llama3.2:3b']);
    expect(rows[0].textContent).toContain('Prompt from the settings');
    expect(rows[1].textContent).toContain('concise-v2');
    expect(rows[1].classList).toContain('leader');
    expect(rows[1].textContent).toContain('90%');
    // No new experiment while one is running.
    expect(element.querySelector('#new-title')).toBeNull();
  });

  it('shows whether B differs from A beyond chance', async () => {
    const { element } = await render([
      experiment({
        comparison: {
          minSample: 10,
          success: { samplesA: 120, samplesB: 110, rateA: 0.6, rateB: 0.75, test: 'z', pValue: 0.0142, verdict: 'B_BETTER' },
          satisfaction: { samplesA: 6, samplesB: 4, rateA: 0.5, rateB: 1, test: null, pValue: null, verdict: 'NOT_ENOUGH_DATA' },
        },
      }),
    ]);

    const success = element.querySelector<HTMLElement>('.running .significance [data-metric=success]')!;
    expect(success.dataset['verdict']).toBe('B_BETTER');
    expect(success.textContent).toContain('B is better (95 %)');
    expect(success.textContent).toContain('p = 0.0142, two-proportion z-test');
    const satisfaction = element.querySelector<HTMLElement>('.running .significance [data-metric=satisfaction]')!;
    expect(satisfaction.textContent).toContain('Not enough data: 10 per variant needed (A: 6, B: 4)');
    expect(satisfaction.textContent).not.toContain('p =');
  });

  it('explains why an experiment was stopped automatically', async () => {
    const { element } = await render([
      experiment({
        status: 'STOPPED',
        stoppedBy: 'system',
        stoppedAt: '2026-10-20T12:00:00Z',
        stopReason: 'Mistral AI was disabled or lost its API key',
      }),
      experiment({ id: 'e0', status: 'STOPPED', stoppedBy: 'admin', stoppedAt: '2026-10-19T12:00:00Z' }),
    ]);

    const notes = Array.from(element.querySelectorAll('.past .auto-stopped'));
    expect(notes).toHaveLength(1);
    expect(notes[0].textContent).toContain('Stopped automatically: Mistral AI was disabled or lost its API key');
  });

  it('stops the running experiment after confirmation', async () => {
    const { fixture, element } = await render([experiment({})]);
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    Array.from(element.querySelectorAll<HTMLButtonElement>('.running button')).find((b) => b.textContent?.trim() === 'Stop')!.click();
    http.expectOne({ method: 'POST', url: '/api/experiments/e1/stop' }).flush(experiment({ status: 'STOPPED' }));
    await settle();
    http.expectOne('/api/experiments').flush([experiment({ status: 'STOPPED', stoppedAt: '2026-10-20T12:00:00Z' })]);
    await fixture.whenStable();

    expect(element.querySelector('.running')).toBeNull();
    expect(element.querySelector('.past')?.textContent).toContain('llama vs qwen');
  });

  it('starts an experiment with two different variants', async () => {
    const { fixture, element } = await render([]);
    const start = Array.from(element.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.trim() === 'Start experiment')!;
    const name = element.querySelector<HTMLInputElement>('.new-form .name input')!;
    name.value = 'Shorter prompt';
    name.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    // Same model and prompt on both sides: not allowed.
    expect(start.disabled).toBe(true);
    expect(element.querySelector('.hint.warn')).not.toBeNull();
    // Only tool-capable models are offered.
    const modelB = element.querySelector<HTMLSelectElement>('select[data-variant=B]')!;
    expect(Array.from(modelB.options).map((o) => o.value)).toEqual(['ollama|qwen3:8b', 'ollama|llama3.2:3b']);

    const promptB = element.querySelector<HTMLSelectElement>('select[data-prompt=B]')!;
    promptB.value = '7';
    promptB.dispatchEvent(new Event('change'));
    const percent = element.querySelector<HTMLInputElement>('.percent input')!;
    percent.value = '20';
    percent.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(start.disabled).toBe(false);

    start.click();
    const req = http.expectOne({ method: 'POST', url: '/api/experiments' });
    expect(req.request.body).toEqual({
      name: 'Shorter prompt',
      a: { provider: 'ollama', model: 'qwen3:8b', promptId: null },
      b: { provider: 'ollama', model: 'qwen3:8b', promptId: 7 },
      bPercent: 20,
    });
    req.flush(experiment({ name: 'Shorter prompt', bPercent: 20 }));
    await settle();
    http.expectOne('/api/experiments').flush([experiment({ name: 'Shorter prompt', bPercent: 20 })]);
  });

  it('saves and deletes prompt versions', async () => {
    const { fixture, element } = await render([]);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const inputs = element.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>('.prompt-form input, .prompt-form textarea');
    inputs[0].value = 'friendly-v1';
    inputs[0].dispatchEvent(new Event('input'));
    inputs[1].value = 'Be friendly.';
    inputs[1].dispatchEvent(new Event('input'));
    await fixture.whenStable();

    element.querySelector<HTMLButtonElement>('.prompt-form button')!.click();
    const create = http.expectOne({ method: 'POST', url: '/api/prompts' });
    expect(create.request.body).toEqual({ name: 'friendly-v1', content: 'Be friendly.' });
    create.flush({ id: 8, name: 'friendly-v1', content: 'Be friendly.', createdBy: 'admin', createdAt: '2026-10-20T11:00:00Z' });
    await settle();
    http.expectOne('/api/prompts').flush(PROMPTS);
    await fixture.whenStable();

    element.querySelector<HTMLButtonElement>('button[aria-label="Delete prompt version concise-v2"]')!.click();
    http.expectOne({ method: 'DELETE', url: '/api/prompts/7' }).flush(null);
    await settle();
    http.expectOne('/api/prompts').flush([]);
  });
});
