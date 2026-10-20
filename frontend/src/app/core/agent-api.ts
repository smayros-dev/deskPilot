import { HttpClient, HttpContext, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_CONFIG } from './api-config';
import { AgentSettings, ApprovalDecision, FeedbackRating, ImageAttachment, Run, RunFeedback, Session } from './models';
import { RunEvent, toRunEvents } from './run-stream';

/** Commands and lookups against the agent REST API. Read-only lists are loaded with httpResource in the pages. */
@Injectable({ providedIn: 'root' })
export class AgentApi {
  private readonly http = inject(HttpClient);
  private readonly config = inject(API_CONFIG);

  /** Starts a conversation for the signed-in user (the backend takes the user from the token). */
  createSession(): Observable<Session> {
    return this.http.post<Session>(`${this.config.baseUrl}/sessions`, null);
  }

  /** Deletes the conversation with its messages (refused with 409 while the agent works on it or waits for approval). */
  deleteSession(sessionId: string): Observable<void> {
    return this.http.delete<void>(`${this.config.baseUrl}/sessions/${sessionId}`);
  }

  renameSession(sessionId: string, title: string): Observable<Session> {
    return this.http.patch<Session>(`${this.config.baseUrl}/sessions/${sessionId}`, { title });
  }

  getSession(sessionId: string, context?: HttpContext): Observable<Session> {
    return this.http.get<Session>(`${this.config.baseUrl}/sessions/${sessionId}`, { context });
  }

  /** Runs the agent; with a local model on CPU the response can take a minute or more. */
  sendMessage(sessionId: string, message: string): Observable<Run> {
    return this.http.post<Run>(`${this.config.baseUrl}/sessions/${sessionId}/messages`, { message });
  }

  /** Like {@link sendMessage}, but emits the run's steps as the agent works (Server-Sent Events). */
  streamMessage(sessionId: string, message: string, image: ImageAttachment | null = null): Observable<RunEvent> {
    return this.stream(`${this.config.baseUrl}/sessions/${sessionId}/messages/stream`, image ? { message, image } : { message });
  }

  listRuns(sessionId: string): Observable<Run[]> {
    return this.http.get<Run[]>(`${this.config.baseUrl}/sessions/${sessionId}/runs`);
  }

  getRun(runId: string, context?: HttpContext): Observable<Run> {
    return this.http.get<Run>(`${this.config.baseUrl}/runs/${runId}`, { context });
  }

  decide(runId: string, decision: ApprovalDecision): Observable<Run> {
    return this.http.post<Run>(`${this.config.baseUrl}/runs/${runId}/approval`, decision);
  }

  /** Like {@link decide}, but emits the steps of the resumed run as they happen. */
  streamDecision(runId: string, decision: ApprovalDecision): Observable<RunEvent> {
    return this.stream(`${this.config.baseUrl}/runs/${runId}/approval/stream`, decision);
  }

  /** Rates the answer of one of the user's finished runs; rating again replaces the earlier rating. */
  rateRun(runId: string, rating: FeedbackRating, comment: string | null): Observable<RunFeedback> {
    return this.http.put<RunFeedback>(`${this.config.baseUrl}/runs/${runId}/feedback`, { rating, comment });
  }

  removeRating(runId: string): Observable<void> {
    return this.http.delete<void>(`${this.config.baseUrl}/runs/${runId}/feedback`);
  }

  getSettings(): Observable<AgentSettings> {
    return this.http.get<AgentSettings>(`${this.config.baseUrl}/settings`);
  }

  updateSettings(settings: AgentSettings): Observable<AgentSettings> {
    return this.http.put<AgentSettings>(`${this.config.baseUrl}/settings`, settings);
  }

  resetSettings(): Observable<AgentSettings> {
    return this.http.delete<AgentSettings>(`${this.config.baseUrl}/settings`);
  }

  // POST with a body and a bearer token, which EventSource cannot do; HttpClient's fetch backend streams the text.
  private stream(url: string, body: unknown): Observable<RunEvent> {
    return this.http
      .post(url, body, {
        headers: new HttpHeaders({ Accept: 'text/event-stream' }),
        observe: 'events',
        reportProgress: true,
        responseType: 'text',
      })
      .pipe(toRunEvents());
  }

  url(path: string): string {
    return `${this.config.baseUrl}${path}`;
  }
}
