import { DatePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { AgentApi } from '../core/agent-api';
import { AuthService } from '../core/auth';
import { EventsService } from '../core/events';
import { Ticket, TicketPriority } from '../core/models';

type PriorityFilter = TicketPriority | 'ALL';

@Component({
  selector: 'app-tickets-page',
  imports: [DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="page-header">
      <div>
        <h1>{{ auth.isReviewer() || auth.isAdmin() ? 'All tickets' : 'Your tickets' }}</h1>
        <p class="muted">Created by the agent after a reviewer approved them.</p>
      </div>
      <button type="button" class="button" (click)="tickets.reload()" [disabled]="tickets.isLoading()">Refresh</button>
    </header>

    <div class="filters" role="group" aria-label="Filter by priority">
      @for (option of priorities; track option) {
        <button type="button" class="chip" [class.selected]="filter() === option" [attr.aria-pressed]="filter() === option" (click)="filter.set(option)">
          {{ option === 'ALL' ? 'All' : option }}
        </button>
      }
    </div>

    @if (tickets.hasValue()) {
      <div class="master-detail">
        <ul class="list card" aria-label="Tickets">
          @for (ticket of filtered(); track ticket.id) {
            <li>
              <button type="button" class="row" [class.selected]="selected()?.id === ticket.id" (click)="selected.set(ticket)">
                <span class="prio" [attr.data-priority]="ticket.priority">{{ ticket.priority }}</span>
                <span class="title">#{{ ticket.id }} {{ ticket.title }}</span>
                <span class="muted">{{ ticket.createdAt | date: 'short' }}</span>
              </button>
            </li>
          } @empty {
            <li class="muted empty">No tickets{{ filter() === 'ALL' ? '' : ' with this priority' }}.</li>
          }
        </ul>

        @if (selected(); as ticket) {
          <article class="card detail">
            <h2>#{{ ticket.id }} {{ ticket.title }}</h2>
            <dl>
              <dt>Priority</dt><dd>{{ ticket.priority }}</dd>
              <dt>Status</dt><dd>{{ ticket.status }}</dd>
              <dt>Requested by</dt><dd>{{ ticket.requestedBy }}</dd>
              <dt>Created</dt><dd>{{ ticket.createdAt | date: 'medium' }}</dd>
            </dl>
            <p>{{ ticket.description }}</p>
          </article>
        }
      </div>
    } @else if (tickets.isLoading()) {
      <p class="muted">Loading tickets…</p>
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
