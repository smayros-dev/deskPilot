import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_CONFIG } from './api-config';

/** A named, immutable version of the system prompt (12m). */
export interface PromptVersion {
  id: number;
  name: string;
  content: string;
  createdBy: string;
  createdAt: string;
}

export type ExperimentStatus = 'RUNNING' | 'STOPPED';

/** Results of one arm of an experiment; rates are 0..1 over finished runs. */
export interface VariantResult {
  variant: 'A' | 'B';
  provider: string;
  model: string;
  /** Null when the variant uses the prompt from the settings. */
  promptVersionId: number | null;
  promptVersionName: string | null;
  runs: number;
  finished: number;
  successRate: number;
  escalationRate: number;
  avgLatencyMillis: number;
  p95LatencyMillis: number;
  avgTokens: number;
  up: number;
  down: number;
  satisfactionRate: number;
}

/** B compared with A at the 95 % level. */
export type Verdict = 'NOT_ENOUGH_DATA' | 'NO_SIGNIFICANT_DIFFERENCE' | 'B_BETTER' | 'B_WORSE';

export interface Significance {
  samplesA: number;
  samplesB: number;
  rateA: number;
  rateB: number;
  /** 'z' (two-proportion z-test) or 'fisher' (exact test, small counts); null without enough data. */
  test: 'z' | 'fisher' | null;
  pValue: number | null;
  verdict: Verdict;
}

export interface Comparison {
  /** Finished runs (or ratings) each variant needs before a verdict. */
  minSample: number;
  success: Significance;
  satisfaction: Significance;
}

export interface Experiment {
  id: string;
  name: string;
  status: ExperimentStatus;
  /** Share of new conversations sent to variant B. */
  bPercent: number;
  startedBy: string;
  startedAt: string;
  stoppedBy: string | null;
  stoppedAt: string | null;
  /** Why DeskPilot stopped it itself (provider disabled, repeated provider errors); null otherwise. */
  stopReason?: string | null;
  variants: VariantResult[];
  comparison?: Comparison;
}

export interface VariantRequest {
  provider: string;
  model: string;
  promptId: number | null;
}

export interface StartExperimentRequest {
  name: string;
  a: VariantRequest;
  b: VariantRequest;
  bPercent: number;
}

/** Prompt versions and A/B experiments (admins only); lists are loaded with httpResource in the pages. */
@Injectable({ providedIn: 'root' })
export class ExperimentsApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_CONFIG).baseUrl;

  readonly experimentsUrl = `${this.baseUrl}/experiments`;
  readonly promptsUrl = `${this.baseUrl}/prompts`;

  start(request: StartExperimentRequest): Observable<Experiment> {
    return this.http.post<Experiment>(this.experimentsUrl, request);
  }

  stop(id: string): Observable<Experiment> {
    return this.http.post<Experiment>(`${this.experimentsUrl}/${id}/stop`, null);
  }

  createPrompt(name: string, content: string): Observable<PromptVersion> {
    return this.http.post<PromptVersion>(this.promptsUrl, { name, content });
  }

  deletePrompt(id: number): Observable<void> {
    return this.http.delete<void>(`${this.promptsUrl}/${id}`);
  }
}
