import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';

import { problemMessage } from './interceptors';

function error(status: number, headers: Record<string, string> = {}, body: unknown = null): HttpErrorResponse {
  return new HttpErrorResponse({ status, statusText: 'Error', headers: new HttpHeaders(headers), error: body });
}

describe('problemMessage', () => {
  it('tells the user how long to wait when they are rate limited', () => {
    expect(problemMessage(error(429, { 'Retry-After': '12' }))).toBe('You are sending messages too fast. Try again in 12 s.');
    expect(problemMessage(error(429))).toBe('You are sending messages too fast. Try again in a moment.');
  });

  it('explains that the model is unavailable when the circuit breaker is open', () => {
    expect(problemMessage(error(503, { 'Retry-After': '30' }))).toBe('The language model is unavailable right now. Try again in 30 s.');
    expect(problemMessage(error(503))).toBe('The language model is unavailable right now. Try again shortly.');
  });

  it('still shows other problem details as before', () => {
    expect(problemMessage(error(422, {}, { title: 'Message rejected', detail: 'Blocked by the content filter.' })))
      .toBe('Blocked by the content filter.');
    expect(problemMessage(error(0))).toContain('backend is not reachable');
  });
});
