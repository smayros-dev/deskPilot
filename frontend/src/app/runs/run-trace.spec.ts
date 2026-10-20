import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { I18n } from '../core/i18n';
import { Run } from '../core/models';
import { RunTrace } from './run-trace';

function routedRun(): Run {
  return {
    id: 'r1', sessionId: 's1', userId: 'alice', model: 'qwen3:8b', specialist: 'network', status: 'COMPLETED',
    userInput: 'My VPN drops', answer: 'Reconnect.',
    steps: [
      { id: 1, type: 'USER_INPUT', name: null, content: 'My VPN drops', error: false, durationMillis: 0, createdAt: '' },
      {
        id: 2, type: 'HANDOFF', name: 'network', error: false, durationMillis: 3, createdAt: '',
        content: 'Handed to the Network & VPN agent (keywords, confidence 1.00). keywords: vpn',
      },
      { id: 3, type: 'ANSWER', name: 'COMPLETED', content: 'Reconnect.', error: false, durationMillis: 0, createdAt: '' },
    ],
    pendingApprovals: [], stats: { iterations: 1, toolCalls: 0, toolErrors: 0, promptTokens: 1, completionTokens: 1, activeMillis: 1 },
    createdAt: '', completedAt: null,
  };
}

describe('RunTrace', () => {
  async function render(run: Run, lang: 'en' | 'fr' = 'en') {
    TestBed.configureTestingModule({ imports: [RunTrace], providers: [provideZonelessChangeDetection(), provideRouter([])] });
    TestBed.inject(I18n).setLang(lang);
    const fixture = TestBed.createComponent(RunTrace);
    fixture.componentRef.setInput('run', run);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows the hand-off to a specialist as a step and a badge', async () => {
    const element = await render(routedRun());

    const handOff = element.querySelector<HTMLElement>('.step[data-type="HANDOFF"]')!;
    expect(handOff.textContent).toContain('🧭');
    expect(handOff.textContent).toContain('Handed to a specialist');
    expect(handOff.textContent).toContain('keywords: vpn');
    expect(element.querySelector('.stats app-specialist-badge')?.textContent).toContain('Network & VPN');
  });

  it('names the specialist in the selected language and shows no badge without routing', async () => {
    const french = await render(routedRun(), 'fr');
    expect(french.querySelector('app-specialist-badge')?.textContent).toContain('Réseau & VPN');

    TestBed.resetTestingModule();
    const plain = await render({ ...routedRun(), specialist: null, steps: [] });
    expect(plain.querySelector('app-specialist-badge')).toBeNull();
    expect(plain.querySelector('app-experiment-badge')).toBeNull();
  });

  it('shows the experiment variant of the run when the API sends it', async () => {
    const element = await render({ ...routedRun(), experiment: { id: 'e1', name: 'llama vs qwen', variant: 'B' } });

    const badge = element.querySelector<HTMLElement>('.stats app-experiment-badge .badge')!;
    expect(badge.textContent).toContain('🧪');
    expect(badge.textContent).toContain('Variant B');
    expect(badge.title).toBe('A/B experiment "llama vs qwen", variant B');
  });
});
