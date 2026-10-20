import { HttpClient } from '@angular/common/http';
import { Injectable, InjectionToken, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_CONFIG } from '../core/api-config';
import { Translate, translateEn } from '../core/i18n';
import { KnowledgeDocument } from '../core/models';

/** Same limits as the backend (DocumentIngestionService, spring.servlet.multipart). */
export const MAX_DOCUMENT_BYTES = 10 * 1024 * 1024;
export const DOCUMENT_EXTENSIONS = ['pdf', 'md', 'markdown', 'txt', 'html', 'htm'];

/** How often the documents list is refreshed while one is being processed, in milliseconds. */
export const DOCUMENTS_POLL_MS = new InjectionToken<number>('DOCUMENTS_POLL_MS', {
  providedIn: 'root',
  factory: () => 2000,
});

/** @returns why the file cannot be uploaded (in the language of `t`, English by default), or null when it can */
export function documentProblem(file: File, t: Translate = translateEn): string | null {
  const extension = file.name.includes('.') ? file.name.split('.').pop()!.toLowerCase() : '';
  if (!DOCUMENT_EXTENSIONS.includes(extension)) {
    return t('documents.unsupported', { name: file.name });
  }
  if (file.size === 0) {
    return t('documents.empty', { name: file.name });
  }
  if (file.size > MAX_DOCUMENT_BYTES) {
    return t('documents.tooLarge', { name: file.name });
  }
  return null;
}

/** Documents uploaded to the knowledge base (admins only); the list is loaded with httpResource in the page. */
@Injectable({ providedIn: 'root' })
export class DocumentsApi {
  private readonly http = inject(HttpClient);
  readonly baseUrl = `${inject(API_CONFIG).baseUrl}/knowledge-base/documents`;

  upload(file: File, title: string | null): Observable<KnowledgeDocument> {
    const form = new FormData();
    form.append('file', file, file.name);
    if (title?.trim()) {
      form.append('title', title.trim());
    }
    return this.http.post<KnowledgeDocument>(this.baseUrl, form);
  }

  reindex(id: number): Observable<KnowledgeDocument> {
    return this.http.post<KnowledgeDocument>(`${this.baseUrl}/${id}/reindex`, null);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
