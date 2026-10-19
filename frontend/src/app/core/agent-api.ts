import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_CONFIG } from './api-config';
import { AgentSettings, ApprovalDecision, Run, Session } from './models';

/** Commands and lookups against the agent REST API. Read-only lists are loaded with httpResource in the pages. */
@Injectable({ providedIn: 'root' })
export class AgentApi {
  private readonly http = inject(HttpClient);
  private readonly config = inject(API_CONFIG);

  createSession(userId: string): Observable<Session> {
    return this.http.post<Session>(`${this.config.baseUrl}/sessions`, { userId });
  }

  getSession(sessionId: string): Observable<Session> {
    return this.http.get<Session>(`${this.config.baseUrl}/sessions/${sessionId}`);
  }

  /** Runs the agent; with a local model on CPU the response can take a minute or more. */
  sendMessage(sessionId: string, message: string): Observable<Run> {
    return this.http.post<Run>(`${this.config.baseUrl}/sessions/${sessionId}/messages`, { message });
  }

  listRuns(sessionId: string): Observable<Run[]> {
    return this.http.get<Run[]>(`${this.config.baseUrl}/sessions/${sessionId}/runs`);
  }

  getRun(runId: string): Observable<Run> {
    return this.http.get<Run>(`${this.config.baseUrl}/runs/${runId}`);
  }

  decide(runId: string, decision: ApprovalDecision): Observable<Run> {
    return this.http.post<Run>(`${this.config.baseUrl}/runs/${runId}/approval`, decision);
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

  url(path: string): string {
    return `${this.config.baseUrl}${path}`;
  }
}
