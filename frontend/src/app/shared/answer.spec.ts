import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { Run } from '../core/models';
import { Answer } from './answer';

function runCiting(): Run {
  return {
    id: 'r1', sessionId: 's1', userId: 'alice', model: 'qwen3:8b', status: 'COMPLETED', userInput: 'q', answer: null,
    steps: [{
      id: 1, type: 'TOOL_RESULT', name: 'search_knowledge_base', error: false, durationMillis: 5, createdAt: '',
      content: JSON.stringify({ articles: [{ id: 1, cite: '[1]', title: 'Connecting to the corporate VPN', content: '…', relevance: 0.8 }] }),
    }],
    pendingApprovals: [], stats: { iterations: 1, toolCalls: 1, toolErrors: 0, promptTokens: 1, completionTokens: 1, activeMillis: 1 },
    createdAt: '', completedAt: null,
  };
}

describe('Answer', () => {
  async function render(text: string, run: Run | null = null) {
    TestBed.configureTestingModule({ imports: [Answer], providers: [provideZonelessChangeDetection(), provideRouter([])] });
    const fixture = TestBed.createComponent(Answer);
    fixture.componentRef.setInput('text', text);
    fixture.componentRef.setInput('run', run);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders Markdown as elements', async () => {
    const element = await render('Switch to **IPSec**:\n\n1. Open *Settings*\n2. Reconnect');

    expect(element.querySelector('strong')?.textContent).toBe('IPSec');
    expect(element.querySelector('em')?.textContent).toBe('Settings');
    expect(element.querySelectorAll('ol li')).toHaveLength(2);
    expect(element.textContent).not.toContain('**');
  });

  it('shows HTML from the model as text, never as markup', async () => {
    const element = await render('<img src=x onerror=alert(1)> <script>alert(2)</script>');

    expect(element.querySelector('img')).toBeNull();
    expect(element.querySelector('script')).toBeNull();
    expect(element.textContent).toContain('<img src=x onerror=alert(1)>');
  });

  it('does not create links for javascript: URLs', async () => {
    const element = await render('[click me](javascript:alert(1)) and [portal](https://vpn.example.com)');

    const links = Array.from(element.querySelectorAll('a'));
    expect(links.map((a) => a.getAttribute('href'))).toEqual(['https://vpn.example.com']);
    expect(links[0].getAttribute('rel')).toContain('noopener');
  });

  it('turns citation markers into numbered chips and lists the sources', async () => {
    const element = await render('Switch the protocol to IPSec [1].', runCiting());

    const chip = element.querySelector<HTMLAnchorElement>('sup a.cite');
    expect(chip?.textContent?.trim()).toBe('1');
    expect(chip?.getAttribute('href')).toBe('/helpdesk/knowledge?article=1');
    expect(element.querySelector('.sources li')?.textContent).toContain('Connecting to the corporate VPN');
  });

  it('leaves an unknown marker as plain text and shows no sources', async () => {
    const element = await render('Something [9].', runCiting());

    expect(element.querySelector('sup')).toBeNull();
    expect(element.textContent).toContain('[9]');
    expect(element.querySelector('.sources')).toBeNull();
  });
});
