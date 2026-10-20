import { DatePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { AgentApi } from '../core/agent-api';
import { AuthService } from '../core/auth';
import { EventsService } from '../core/events';
import { I18n } from '../core/i18n';
import { Ticket, TicketPriority } from '../core/models';

type PriorityFilter = TicketPriority | 'ALL';

@Component({
  selector: 'app-tickets-page',
  imports: [DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="page-header">
      <div>
        <h1>{{ auth.isReviewer() || auth.isAdmin() ? i18n.t('tickets.all') : i18n.t('tickets.yours') }}</h1>
        <p class="muted">{{ i18n.t('tickets.subtitle') }}</p>
      </div>
      <button type="button" class="button" (click)="tickets.reload()" [disabled]="tickets.isLoading()">{{ i18n.t('common.refresh') }}</button>
    </header>

    <div class="filters" role="group" [attr.aria-label]="i18n.t('tickets.filter')">
      @for (option of priorities; track option) {
        <button type="button" class="chip" [class.selected]="filter() === option" [attr.aria-pressed]="filter() === option" (click)="filter.set(option)">
          {{ option === 'ALL' ? i18n.t('common.all') : option }}
        </button>
      }
    </div>

    @if (tickets.hasValue()) {
      <div class="master-detail">
        <ul class="list card" [attr.aria-label]="i18n.t('tickets.list')">
          @for (ticket of filtered(); track ticket.id) {
            <li>
              <button type="button" class="row" [class.selected]="selected()?.id === ticket.id" (click)="selected.set(ticket)">
                <span class="prio" [attr.data-priority]="ticket.priority">{{ ticket.priority }}</span>
                <span class="title">#{{ ticket.id }} {{ ticket.title }}</span>
                <span class="muted">{{ ticket.createdAt | date: 'short' : undefined : i18n.locale() }}</span>
              </button>
            </li>
          } @empty {
            <li class="muted empty">{{ filter() === 'ALL' ? i18n.t('tickets.none') : i18n.t('tickets.noneWithPriority') }}</li>
          }
        </ul>

        @if (selected(); as ticket) {
          <article class="card detail">
            <h2>#{{ ticket.id }} {{ ticket.title }}</h2>
            <dl>
              <dt>{{ i18n.t('tickets.priority') }}</dt><dd>{{ ticket.priority }}</dd>
              <dt>{{ i18n.t('tickets.status') }}</dt><dd>{{ ticket.status }}</dd>
              <dt>{{ i18n.t('tickets.requestedBy') }}</dt><dd>{{ ticket.requestedBy }}</dd>
              <dt>{{ i18n.t('tickets.created') }}</dt><dd>{{ ticket.createdAt | date: 'medium' : undefined : i18n.locale() }}</dd>
            </dl>
            <p>{{ ticket.description }}</p>
          </article>
        }
      </div>
    } @else if (tickets.isLoading()) {
      <p class="muted">{{ i18n.t('tickets.loading') }}</p>
    }
  `,
  styles: `
    .filters { display: flex; flex-wrap: wrap; gap: 0.5rem; margin-bottom: 1rem; }
    .master-detail { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 1.5rem; align-items: start; }
    .list { list-style: none; margin: 0; padding: 0.5rem; }
    .row { all: unset; box-sizing: border-box; width: 100%; display: grid; grid-template-columns: 5.5rem 1fr auto; gap: 0.75rem; align-items: center; padding: 0.6rem 0.75rem; border-radius: var(--radius); cursor: pointer; }
    .row:hover { background: var(--surface-muted); }
    .row.selected { background: var(--accent-bg); }
    .row:focus-visible { outline: 2px solid var(--accent); }
    .title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .prio { font-size: 0.7rem; font-weight: 700; text-align: center; padding: 0.15rem 0.4rem; border-radius: 999px; background: var(--surface-muted); }
    [data-priority='HIGH'] { background: var(--warn-bg); color: var(--warn); }
    [data-priority='CRITICAL'] { background: var(--danger-bg); color: var(--danger); }
    .empty { padding: 1rem; }
    .detail h2 { font-size: 1.1rem; margin-top: 0; }
    dl { display: grid; grid-template-columns: max-content 1fr; gap: 0.3rem 1rem; }
    dt { color: var(--text-muted); }
    dd { margin: 0; }
    .detail p { white-space: pre-wrap; }
    @media (max-width: 800px) { .master-detail { grid-template-columns: 1fr; } }
  `,
})
export class TicketsPage {
  private readonly api = inject(AgentApi);
  protected readonly auth = inject(AuthService);
  protected readonly i18n = inject(I18n);

  protected readonly priorities: PriorityFilter[] = ['ALL', 'CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
  protected readonly tickets = httpResource<Ticket[]>(() => this.api.url('/tickets'), { defaultValue: [] });

  constructor() {
    // A ticket was created (after a reviewer approved it): show it without a manual refresh.
    inject(EventsService).tickets$.pipe(takeUntilDestroyed()).subscribe(() => this.tickets.reload());
  }
  protected readonly filter = signal<PriorityFilter>('ALL');

  protected readonly filtered = computed(() => {
    const filter = this.filter();
    const tickets = this.tickets.hasValue() ? this.tickets.value() : [];
    return filter === 'ALL' ? tickets : tickets.filter((t) => t.priority === filter);
  });

  /** Follows the list: re-selects the first ticket whenever the filtered list changes, but stays user-selectable. */
  protected readonly selected = linkedSignal<Ticket[], Ticket | null>({
    source: this.filtered,
    computation: (tickets, previous) => tickets.find((t) => t.id === previous?.value?.id) ?? tickets[0] ?? null,
  });
}
