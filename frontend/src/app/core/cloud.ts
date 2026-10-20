import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_CONFIG } from './api-config';

/** The local provider; every other provider id is a cloud provider. */
export const LOCAL_PROVIDER = 'ollama';

export interface ProviderStatus {
  id: string;
  label: string;
  enabled: boolean;
  /** Where the API key comes from; the key itself is never sent to the browser. */
  keySource: 'ENVIRONMENT' | 'STORED' | 'NONE';
  keyHint: string | null;
  baseUrl: string;
  keysUrl: string;
  /** Enabled and with a key. */
  available: boolean;
  updatedBy: string | null;
  updatedAt: string | null;
}

export interface CloudModel {
  id: string;
  supportsTools: boolean;
  supportsVision: boolean;
}

export interface ProviderTestResult {
  ok: boolean;
  latencyMillis: number;
  models: number;
  error: string | null;
}

/** Provider + model as one select value, e.g. "mistral|mistral-small-latest". */
export function modelChoice(provider: string | null | undefined, model: string): string {
  return `${provider || LOCAL_PROVIDER}|${model}`;
}

export function parseModelChoice(value: string): { provider: string; model: string } {
  const separator = value.indexOf('|');
  return separator < 0
    ? { provider: LOCAL_PROVIDER, model: value }
    : { provider: value.slice(0, separator), model: value.slice(separator + 1) };
}

export function isCloudProvider(provider: string | null | undefined): boolean {
  return !!provider && provider !== LOCAL_PROVIDER;
}

/** Cloud providers (admins only). */
@Injectable({ providedIn: 'root' })
export class ProvidersApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_CONFIG).baseUrl}/providers`;

  readonly listUrl = this.baseUrl;

  update(id: string, change: { enabled?: boolean; apiKey?: string; removeKey?: boolean }): Observable<ProviderStatus> {
    return this.http.patch<ProviderStatus>(`${this.baseUrl}/${id}`, change);
  }

  test(id: string): Observable<ProviderTestResult> {
    return this.http.post<ProviderTestResult>(`${this.baseUrl}/${id}/test`, null);
  }

  models(id: string): Observable<CloudModel[]> {
    return this.http.get<CloudModel[]>(`${this.baseUrl}/${id}/models`);
  }
}
