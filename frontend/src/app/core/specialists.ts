import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_CONFIG } from './api-config';

export interface SpecialistExample {
  question: string;
  approach: string;
}

/** A specialist agent (12f); the fields after `examples` are only sent to admins (13g). */
export interface SpecialistInfo {
  id: string;
  name: string;
  description: string;
  keywords: string[];
  /** Installed tools it may use. */
  tools: string[];
  /** Number of example requests. */
  examples: number;
  enabled: boolean;
  /** The general agent: handles everything else and cannot be changed. */
  builtIn: boolean;
  prompt: string | null;
  /** Tool patterns as stored (`mcp_*` style prefixes); null = every tool. */
  allowedTools: string[] | null;
  exampleList: SpecialistExample[] | null;
  sortOrder: number | null;
  updatedBy: string | null;
  updatedAt: string | null;
}

export interface SpecialistRequest {
  id?: string;
  name: string;
  description: string;
  keywords: string[];
  tools: string[] | null;
  prompt: string;
  examples: SpecialistExample[];
  enabled: boolean;
  sortOrder: number;
}

export type RoutingMethod = 'keywords' | 'meaning' | 'previous' | 'model' | 'default';

export interface RoutePreview {
  specialist: string;
  name: string;
  method: RoutingMethod;
  /** Keyword share or similarity of the choice; null when chosen otherwise. */
  confidence: number | null;
  detail: string;
  keywords: Record<string, string[]>;
  /** Closest description or example per specialist; empty when the embedding model is down. */
  similarities: Record<string, number>;
}

/** Specialist agents: listed for everyone, edited by admins (13g). */
@Injectable({ providedIn: 'root' })
export class SpecialistsApi {
  private readonly http = inject(HttpClient);
  readonly listUrl = `${inject(API_CONFIG).baseUrl}/specialists`;

  create(request: SpecialistRequest): Observable<SpecialistInfo> {
    return this.http.post<SpecialistInfo>(this.listUrl, request);
  }

  update(id: string, request: SpecialistRequest): Observable<SpecialistInfo> {
    return this.http.put<SpecialistInfo>(`${this.listUrl}/${id}`, request);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.listUrl}/${id}`);
  }

  restoreDefaults(): Observable<SpecialistInfo[]> {
    return this.http.post<SpecialistInfo[]>(`${this.listUrl}/restore-defaults`, null);
  }

  preview(message: string): Observable<RoutePreview> {
    return this.http.post<RoutePreview>(`${this.listUrl}/route-preview`, { message });
  }
}
