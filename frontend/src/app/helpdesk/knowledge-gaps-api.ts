import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_CONFIG } from '../core/api-config';

export type KnowledgeGapStatus = 'OPEN' | 'DRAFTED' | 'PUBLISHED' | 'DISMISSED';
export type GapReason = 'ESCALATED' | 'TICKET' | 'NEGATIVE_FEEDBACK' | 'NOT_FOUND';

export interface GapQuestion {
  runId: string;
  question: string;
  reason: GapReason;
  askedAt: string;
}

export interface KnowledgeGap {
  id: number;
  /** The most representative question of the group. */
  title: string;
  status: KnowledgeGapStatus;
  questionCount: number;
  reasons: Partial<Record<GapReason, number>>;
  /** The most recent questions (at most 5). */
  questions: GapQuestion[];
  draftTitle: string | null;
  draftContent: string | null;
  draftTags: string | null;
  draftedBy: string | null;
  articleId: number | null;
  detectedAt: string;
  updatedAt: string;
  decidedBy: string | null;
  decidedAt: string | null;
}

export interface GapDetection {
  candidates: number;
  covered: number;
  newGaps: number;
  grownGaps: number;
  unassigned: number;
}

export interface GapDraft {
  title: string;
  content: string;
  tags: string;
}

/** Knowledge gaps (reviewers, 12d); the list is loaded with httpResource in the panel. */
@Injectable({ providedIn: 'root' })
export class KnowledgeGapsApi {
  private readonly http = inject(HttpClient);
  readonly baseUrl = `${inject(API_CONFIG).baseUrl}/knowledge-gaps`;

  detect(): Observable<GapDetection> {
    return this.http.post<GapDetection>(`${this.baseUrl}/detect`, null);
  }

  /** The model writes a draft; this waits for the model (minutes on CPU). */
  draft(id: number): Observable<KnowledgeGap> {
    return this.http.post<KnowledgeGap>(`${this.baseUrl}/${id}/draft`, null);
  }

  saveDraft(id: number, draft: GapDraft): Observable<KnowledgeGap> {
    return this.http.put<KnowledgeGap>(`${this.baseUrl}/${id}/draft`, draft);
  }

  publish(id: number): Observable<KnowledgeGap> {
    return this.http.post<KnowledgeGap>(`${this.baseUrl}/${id}/publish`, null);
  }

  dismiss(id: number): Observable<KnowledgeGap> {
    return this.http.post<KnowledgeGap>(`${this.baseUrl}/${id}/dismiss`, null);
  }
}
