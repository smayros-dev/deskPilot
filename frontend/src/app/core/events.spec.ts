import { HttpEventType, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { EVENTS_RECONNECT_MS, EventsService } from './events';
import { Run, Ticket } from './models';

function sse(...events: [string, unknown][]): string {
  return events.map(([name, data]) => `event:${name}\ndata:${JSON.stringify(data)}\n\n`).join('');
}

/** Waits (real time) for the next connection attempt; match() takes it out of the queue. */
async function nextConnection(http: HttpTestingController, timeoutMs = 5000): Promise<TestRequest> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const [req] = http.match('/api/events');
    if (req) {
      return req;
    }
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  throw new Error('no connection attempt');
}

describe('EventsService', () => {
  let events: EventsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: EVENTS_RECONNECT_MS, useValue: 10 },
      ],
    });
    events = TestBed.inject(EventsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => events.stop());

  it('opens the stream once and publishes connection state, approval count, runs and tickets', async () => {
    const runs: Run[] = [];
    const tickets: Ticket[] = [];
    events.runs$.subscribe((run) => runs.push(run));
    events.tickets$.subscribe((ticket) => tickets.push(ticket));

    events.start();
    events.start();
    const req = await nextConnection(http);
    expect(req.request.headers.get('Accept')).toBe('text/event-stream');
    http.expectNone('/api/events');

    const text = sse(
      ['connected', { user: 'bob' }],
      ['approvals', { pending: 2 }],
      ['run', { id: 'r1', status: 'COMPLETED' }],
      ['ticket', { id: 7, title: 'Docking station' }],
    );
    req.event({ type: HttpEventType.DownloadProgress, loaded: text.length, partialText: ':keep-alive\n\n' + text });

    expect(events.connected()).toBe(true);
    expect(events.pendingApprovals()).toBe(2);
    expect(runs.map((r) => r.id)).toEqual(['r1']);
    expect(tickets.map((t) => t.id)).toEqual([7]);
  });

  it('reconnects when the connection fails or ends, and reports being disconnected meanwhile', async () => {
    events.start();
    const first = await nextConnection(http);
    first.event({ type: HttpEventType.DownloadProgress, loaded: 1, partialText: sse(['connected', { user: 'alice' }]) });
    expect(events.connected()).toBe(true);

    first.error(new ProgressEvent('error'));
    expect(events.connected()).toBe(false);
    const second = await nextConnection(http);

    second.flush(sse(['connected', { user: 'alice' }]));
    expect(events.connected()).toBe(false);
    await nextConnection(http);
  });

  it('stops for good when asked', async () => {
    events.start();
    const req = await nextConnection(http);

    events.stop();

    expect(req.cancelled).toBe(true);
    await new Promise((resolve) => setTimeout(resolve, 50));
    http.expectNone('/api/events');
  });
});
