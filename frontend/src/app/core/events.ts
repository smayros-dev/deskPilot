import { HttpClient, HttpContext, HttpHeaders } from '@angular/common/http';
import { Injectable, InjectionToken, inject, signal } from '@angular/core';
import { Subject, Subscription, defer, finalize, repeat, retry, tap, timer } from 'rxjs';

import { API_CONFIG, SILENT_ERRORS } from './api-config';
import { Run, Ticket } from './models';
import { SseMessage, toSseMessages } from './sse';

/** First reconnect delay in milliseconds; it doubles after each failed attempt, up to {@link MAX_RECONNECT_MS}. */
export const EVENTS_RECONNECT_MS = new InjectionToken<number>('EVENTS_RECONNECT_MS', {
  providedIn: 'root',
  factory: () => 1000,
});
export const MAX_RECONNECT_MS = 30_000;

/**
 * Live updates of the signed-in user from `GET /api/events` (Server-Sent Events): runs that pause, resume or finish,
 * tickets created for them, and — for reviewers — the number of runs waiting for approval. Read with HttpClient so
 * the token interceptor applies; reconnects by itself when the connection drops or ends.
 */
@Injectable({ providedIn: 'root' })
export class EventsService {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(API_CONFIG).baseUrl}/events`;
  private readonly reconnectMs = inject(EVENTS_RECONNECT_MS);

  private readonly _connected = signal(false);
  private readonly _pendingApprovals = signal<number | null>(null);
  private readonly runs = new Subject<Run>();
  private readonly tickets = new Subject<Ticket>();
  private subscription: Subscription | null = null;

  /** True while the stream is open; pages fall back to polling when it is not. */
  readonly connected = this._connected.asReadonly();
  /** Runs waiting for approval (reviewers and admins only; null for others or before the first update). */
  readonly pendingApprovals = this._pendingApprovals.asReadonly();
  readonly runs$ = this.runs.asObservable();
  readonly tickets$ = this.tickets.asObservable();

  start(): void {
    if (this.subscription) {
      return;
    }
    this.subscription = defer(() =>
      this.http.get(this.url, {
        headers: new HttpHeaders({ Accept: 'text/event-stream' }),
        context: new HttpContext().set(SILENT_ERRORS, true),
        observe: 'events',
        reportProgress: true,
        responseType: 'text',
      }),
    )
      .pipe(
        toSseMessages(),
        tap((message) => this.handle(message)),
        finalize(() => this._connected.set(false)),
        // The server ends a connection after a while: open the next one.
        repeat({ delay: () => timer(this.reconnectMs) }),
        // Network error or server down: retry, waiting longer each time (reset once events flow again).
        retry({
          delay: (_error, attempt) => {
            this._connected.set(false);
            return timer(Math.min(MAX_RECONNECT_MS, this.reconnectMs * 2 ** (attempt - 1)));
          },
          resetOnSuccess: true,
        }),
      )
      .subscribe();
  }

  stop(): void {
    this.subscription?.unsubscribe();
    this.subscription = null;
    this._connected.set(false);
  }

  private handle(message: SseMessage): void {
    const payload = JSON.parse(message.data);
    switch (message.event) {
      case 'connected':
        this._connected.set(true);
        break;
      case 'approvals':
        this._pendingApprovals.set((payload as { pending: number }).pending);
        break;
      case 'run':
        this.runs.next(payload as Run);
        break;
      case 'ticket':
        this.tickets.next(payload as Ticket);
        break;
    }
  }
}
