import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { I18n } from '../core/i18n';
import { provideFakeAuth } from '../core/testing';
import { FeedbackButtons } from './feedback-buttons';

describe('FeedbackButtons in other languages', () => {
  afterEach(() => {
    localStorage.clear();
    document.documentElement.lang = 'en';
    document.documentElement.dir = 'ltr';
  });

  async function render() {
    TestBed.configureTestingModule({
      imports: [FeedbackButtons],
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting(), provideFakeAuth('alice')],
    });
    const fixture = TestBed.createComponent(FeedbackButtons);
    fixture.componentRef.setInput('runId', 'r1');
    fixture.componentRef.setInput('feedback', { rating: 'UP', comment: null, updatedAt: '2026-10-20T10:00:00Z' });
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement, i18n: TestBed.inject(I18n) };
  }

  it('renders in French', async () => {
    localStorage.setItem('deskpilot.lang', 'fr');
    const { element } = await render();
    expect(element.querySelector('.thanks')!.textContent).toContain('Merci pour votre avis');
    expect(element.querySelector('button')!.getAttribute('aria-label')).toBe('Utile');
  });

  it('switches to Arabic at runtime and turns the page right-to-left', async () => {
    localStorage.setItem('deskpilot.lang', 'en');
    const { fixture, element, i18n } = await render();
    expect(element.querySelector('.thanks')!.textContent).toContain('Thanks for the feedback');

    i18n.setLang('ar');
    await fixture.whenStable();

    expect(element.querySelector('.thanks')!.textContent).toContain('شكرًا على تقييمك');
    expect(element.querySelector('button')!.getAttribute('aria-label')).toBe('مفيدة');
    expect(document.documentElement.getAttribute('dir')).toBe('rtl');
    expect(document.documentElement.getAttribute('lang')).toBe('ar');
  });
});
