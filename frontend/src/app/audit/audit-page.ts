import { DatePipe } from '@angular/common';
import { HttpClient, httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { API_CONFIG } from '../core/api-config';
import { saveBlob } from '../core/download';
import { I18n } from '../core/i18n';

export const AUDIT_ACTIONS = [
  'APPROVAL_APPROVED',
  'APPROVAL_REJECTED',
  'SETTINGS_CHANGED',
  'SETTINGS_RESET',
  'DOCUMENT_UPLOADED',
  'DOCUMENT_REINDEXED',
  'DOCUMENT_DELETED',
  'EVALUATION_STARTED',
  'EVALUATION_CANCELLED',
  'CONVERSATION_DELETED',
  'MEMORY_DELETED',
  'MEMORIES_DELETED',
  'USER_DATA_EXPORTED',
  'USER_DATA_DELETED',
  'RETENTION_CLEANUP',
  'PROVIDER_UPDATED',
  'PROVIDER_KEY_SET',
  'PROVIDER_KEY_REMOVED',
  'PROMPT_VERSION_SAVED',
  'PROMPT_VERSION_DELETED',
  'EXPERIMENT_STARTED',
  'EXPERIMENT_STOPPED',
  'KNOWLEDGE_GAPS_DETECTED',
  'KNOWLEDGE_GAP_DRAFTED',
  'KNOWLEDGE_GAP_PUBLISHED',
  'KNOWLEDGE_GAP_DISMISSED',
] as const;

export type AuditAction = (typeof AUDIT_ACTIONS)[number];

export interface AuditEvent {
  id: number;
  at: string;
  actor: string;
  action: AuditAction;
  targetType: string | null;
  targetId: string | null;
  details: string | null;
}

export interface AuditEventPage {
  items: AuditEvent[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

const PAGE_SIZE = 50;

/** Admins: who approved what, changed which settings, managed documents or evaluations, deleted personal data. */
@Component({
  selector: 'app-audit-page',
  imports: [DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="page-header">
      <div>
        <h1>{{ i18n.t('audit.title') }}</h1>
        <p class="muted">{{ i18n.t('audit.subtitle') }}</p>
      </div>
      <button type="button" class="button" [disabled]="exporting()" (click)="exportCsv()">{{ i18n.t('audit.export') }}</button>
    </header>

    <form class="card filters" (submit)="$event.preventDefault()">
      <label>
        <span>{{ i18n.t('audit.action') }}</span>
        <select [value]="action()" (change)="setFilter(action, $any($event.target).value)">
          <option value="">{{ i18n.t('audit.allActions') }}</option>
          @for (option of actions; track option) {
            <option [value]="option">{{ label(option) }}</option>
          }
        </select>
      </label>
      <label>
        <span>{{ i18n.t('audit.user') }}</span>
        <input type="text" [placeholder]="i18n.t('audit.userPlaceholder')" [value]="actor()" (change)="setFilter(actor, $any($event.target).value)" />
      </label>
      <label>
        <span>{{ i18n.t('audit.from') }}</span>
        <input type="date" [value]="from()" (change)="setFilter(from, $any($event.target).value)" />
      </label>
      <label>
        <span>{{ i18n.t('audit.to') }}</span>
        <input type="date" [value]="to()" (change)="setFilter(to, $any($event.target).value)" />
      </label>
    </form>

    @if (events.hasValue()) {
      @let result = events.value();
      <div class="card table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">{{ i18n.t('audit.colWhen') }}</th>
              <th scope="col">{{ i18n.t('audit.colUser') }}</th>
              <th scope="col">{{ i18n.t('audit.colAction') }}</th>
              <th scope="col">{{ i18n.t('audit.colTarget') }}</th>
              <th scope="col">{{ i18n.t('audit.colDetails') }}</th>
            </tr>
          </thead>
          <tbody>
            @for (event of result.items; track event.id) {
              <tr>
                <td><time [attr.datetime]="event.at">{{ event.at | date: 'medium' : undefined : i18n.locale() }}</time></td>
                <td>{{ event.actor }}</td>
                <td><span class="action" [attr.data-action]="event.action">{{ label(event.action) }}</span></td>
                <td>
                  @if (event.targetType) {
                    {{ event.targetType }} <code>{{ event.targetId }}</code>
                  }
                </td>
                <td class="details">{{ event.details }}</td>
              </tr>
            } @empty {
              <tr><td colspan="5" class="muted">{{ i18n.t('audit.none') }}</td></tr>
            }
          </tbody>
        </table>
      </div>
      @if (result.totalPages > 1) {
        <nav class="pager" [attr.aria-label]="i18n.t('audit.pages')">
          <button type="button" class="button" [disabled]="page() === 0" (click)="page.set(page() - 1)">{{ i18n.t('audit.newer') }}</button>
          <span class="muted">{{ i18n.t('audit.page', { page: result.page + 1, pages: result.totalPages, total: result.total }) }}</span>
          <button type="button" class="button" [disabled]="page() + 1 >= result.totalPages" (click)="page.set(page() + 1)">{{ i18n.t('audit.older') }}</button>
        </nav>
      }
    } @else if (events.isLoading()) {
      <p class="muted">{{ i18n.t('common.loading') }}</p>
    }
  `,
  styles: `
    :host { display: grid; gap: 1rem; }
    .filters { display: flex; flex-wrap: wrap; gap: 0.75rem 1rem; align-items: flex-end; }
    .filters label { display: grid; gap: 0.25rem; font-size: 0.85rem; }
    .table-wrap { overflow-x: auto; padding: 0; }
    table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
    th, td { text-align: left; padding: 0.5rem 0.75rem; border-bottom: 1px solid var(--border); vertical-align: top; }
    td:first-child { white-space: nowrap; }
    .details { overflow-wrap: anywhere; max-width: 40rem; }
    .action { font-weight: 600; white-space: nowrap; }
    .action[data-action$='DELETED'], .action[data-action='APPROVAL_REJECTED'] { color: var(--danger); }
    .pager { display: flex; align-items: center; justify-content: center; gap: 1rem; }
  `,
})
export class AuditPage {
  protected readonly i18n = inject(I18n);
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_CONFIG).baseUrl}/audit`;

  protected readonly actions = AUDIT_ACTIONS;
  protected readonly action = signal('');
  protected readonly actor = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly page = signal(0);
  protected readonly exporting = signal(false);

  /** Query parameters of the current filters (also used by the CSV export). */
  private readonly filterParams = computed(() => {
    const params: Record<string, string> = {};
    if (this.action()) {
      params['action'] = this.action();
    }
    if (this.actor().trim()) {
      params['actor'] = this.actor().trim();
    }
    if (this.from()) {
      params['from'] = new Date(`${this.from()}T00:00:00`).toISOString();
    }
    if (this.to()) {
      // "To" is a whole day in the user's time zone: the backend bound is exclusive, so use the next midnight.
      const end = new Date(`${this.to()}T00:00:00`);
      end.setDate(end.getDate() + 1);
      params['to'] = end.toISOString();
    }
    return params;
  });

  protected readonly events = httpResource<AuditEventPage>(() => ({
    url: this.baseUrl,
    params: { ...this.filterParams(), page: String(this.page()), size: String(PAGE_SIZE) },
  }));

  protected setFilter(filter: ReturnType<typeof signal<string>>, value: string): void {
    filter.set(value);
    this.page.set(0);
  }

  protected label(action: AuditAction): string {
    return this.i18n.t(`auditAction.${action}`);
  }

  protected async exportCsv(): Promise<void> {
    this.exporting.set(true);
    try {
      const csv = await firstValueFrom(
        this.http.get(`${this.baseUrl}/export`, { params: this.filterParams(), responseType: 'blob' }),
      );
      saveBlob(csv, 'deskpilot-audit.csv');
    } catch {
      // Toast shown by the error interceptor.
    } finally {
      this.exporting.set(false);
    }
  }
}
