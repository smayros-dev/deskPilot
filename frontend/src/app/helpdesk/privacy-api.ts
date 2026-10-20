import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_CONFIG } from '../core/api-config';

/** The signed-in user's own data: delete memories, export everything, delete everything (GDPR). */
@Injectable({ providedIn: 'root' })
export class PrivacyApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_CONFIG).baseUrl}/me`;

  deleteMemory(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/memories/${id}`);
  }

  deleteAllMemories(): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/memories`);
  }

  exportData(): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/export`, { responseType: 'blob' });
  }

  deleteAllData(): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/data`);
  }
}
