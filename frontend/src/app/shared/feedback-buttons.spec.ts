import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { errorInterceptor } from '../core/interceptors';
import { RunFeedback } from '../core/models';
import { provideFakeAuth } from '../core/testing';
import { FeedbackButtons } from './feedback-buttons';

describe('FeedbackButtons', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<FeedbackButtons>;
  let element: HTMLElement;

  async function render(feedback: RunFeedback | null = null) {
    TestBed.configureTestingModule({
      imports: [FeedbackButtons],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        provideFakeAuth('alice'),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(FeedbackButtons);
    fixture.componentRef.setInput('runId', 'r1');
    fixture.componentRef.setInput('feedback', feedback);
    element = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  }

  /** Lets the component's awaited request settle (it resumes after the flush), then renders. */
  async function settle() {
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();
  }

  const button = (label: string) => element.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)!;

  afterEach(() => http.verify());

  it('shows a thumbs up at once and keeps the saved rating', async () => {
    await render();

    button('Helpful').click();
    await fixture.whenStable();
    expect(button('Helpful').getAttribute('aria-pressed')).toBe('true');

    const req = http.expectOne({ method: 'PUT', url: '/api/runs/r1/feedback' });
    expect(req.request.body).toEqual({ rating: 'UP', comment: null });
    req.flush({ rating: 'UP', comment: null, updatedAt: '2026-10-19T12:00:00Z' });
    await settle();

    expect(fixture.componentInstance.feedback()).toEqual({ rating: 'UP', comment: null, updatedAt: '2026-10-19T12:00:00Z' });
    expect(element.textContent).toContain('Thanks for the feedback');
  });

  it('asks for a comment after a thumbs down and sends it', async () => {
    await render();

    button('Not helpful').click();
    await fixture.whenStable();
    http.expectOne('/api/runs/r1/feedback').flush({ rating: 'DOWN', comment: null, updatedAt: '2026-10-19T12:00:00Z' });
    await settle();

    const input = element.querySelector<HTMLInputElement>('.comment-form input')!;
    input.value = 'The portal link is wrong';
    input.dispatchEvent(new Event('input'));
    element.querySelector<HTMLButtonElement>('.comment-form button[type=submit]')!.click();
    await fixture.whenStable();

    const req = http.expectOne({ method: 'PUT', url: '/api/runs/r1/feedback' });
    expect(req.request.body).toEqual({ rating: 'DOWN', comment: 'The portal link is wrong' });
    req.flush({ rating: 'DOWN', comment: 'The portal link is wrong', updatedAt: '2026-10-19T12:01:00Z' });
    await settle();

    expect(element.querySelector('.comment-form')).toBeNull();
    expect(element.querySelector('q')?.textContent).toBe('The portal link is wrong');
  });

  it('restores the previous rating when saving fails', async () => {
    const saved: RunFeedback = { rating: 'UP', comment: null, updatedAt: '2026-10-19T11:00:00Z' };
    await render(saved);
    expect(button('Helpful').getAttribute('aria-pressed')).toBe('true');

    button('Not helpful').click();
    await fixture.whenStable();
    expect(button('Not helpful').getAttribute('aria-pressed')).toBe('true');

    http.expectOne('/api/runs/r1/feedback').flush({ title: 'Conflict', detail: 'Run r1 is not finished yet' }, { status: 409, statusText: 'Conflict' });
    await settle();

    expect(fixture.componentInstance.feedback()).toEqual(saved);
    expect(button('Helpful').getAttribute('aria-pressed')).toBe('true');
    expect(element.querySelector('.comment-form')).toBeNull();
  });

  it('removes the rating when the selected button is clicked again', async () => {
    await render({ rating: 'UP', comment: null, updatedAt: '2026-10-19T11:00:00Z' });

    button('Helpful').click();
    await fixture.whenStable();

    http.expectOne({ method: 'DELETE', url: '/api/runs/r1/feedback' }).flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    expect(fixture.componentInstance.feedback()).toBeNull();
    expect(button('Helpful').getAttribute('aria-pressed')).toBe('false');
  });
});
