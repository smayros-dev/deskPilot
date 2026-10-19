import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_CONFIG } from '../core/api-config';

export type Outcome = 'ANSWERED' | 'APPROVAL' | 'ESCALATED' | 'BLOCKED' | 'FAILED';
export type EvaluationStatus = 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'FAILED';

export interface Scenario {
  id: string;
  category: string;
  question: string;
  expect: {
    outcome: Outcome;
    tools: string[];
    forbiddenTools: string[];
    contains: string[];
    notContains: string[];
    approvalContains: string[];
  };
}

export interface CategoryScore {
  category: string;
  scenarios: number;
  passed: number;
}

export interface ScenarioResult {
  scenarioId: string;
  category: string;
  question: string;
  passed: boolean;
  expectedOutcome: Outcome;
  outcome: Outcome;
  tools: string[];
  failures: string[];
  answer: string | null;
  runId: string | null;
  latencyMillis: number;
  iterations: number;
  tokens: number;
}

export interface Evaluation {
  id: string;
  model: string;
  status: EvaluationStatus;
  scenarioCount: number;
  completed: number;
  passed: number;
  passRate: number;
  avgLatencyMillis: number;
  p95LatencyMillis: number;
  categories: CategoryScore[];
  startedBy: string;
  startedAt: string;
  finishedAt: string | null;
  error: string | null;
  results: ScenarioResult[];
}

/** Commands of the evaluation runner (admins only); lists are loaded with httpResource in the page. */
@Injectable({ providedIn: 'root' })
export class EvaluationApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_CONFIG).baseUrl}/evaluations`;

  readonly listUrl = this.baseUrl;
  readonly scenariosUrl = `${this.baseUrl}/scenarios`;

  detailUrl(id: string): string {
    return `${this.baseUrl}/${id}`;
  }

  start(model: string, categories: string[]): Observable<Evaluation> {
    return this.http.post<Evaluation>(this.baseUrl, { model, categories });
  }

  cancel(id: string): Observable<Evaluation> {
    return this.http.post<Evaluation>(`${this.baseUrl}/${id}/cancel`, null);
  }
}
