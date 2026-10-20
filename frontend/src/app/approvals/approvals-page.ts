import { DatePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { ApprovalCard } from '../chat/approval-card';
import { AgentApi } from '../core/agent-api';
import { EventsService } from '../core/events';
import { I18n } from '../core/i18n';
import { ApprovalDecision, Run } from '../core/models';
import { Notifications } from '../core/notifications';

/** Reviewers' queue: every run, from any user, paused until a human approves or rejects its action. */
@Component({
  selector: 'app-approvals-page',
  imports: [ApprovalCard, DatePipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="page-header">
      <div>
        <h1>{{ i18n.t('approvals.title') }}</h1>
        <p class="muted">{{ i18n.t('approvals.subtitle') }}</p>
      </div>
      <button type="button" class="button" (click)="queue.reload()" [disabled]="queue.isLoading()">{{ i18n.t('common.refresh') }}</button>
    </header>

    @if (queue.hasValue()) {
      <ol class="queue">
        @for (run of queue.value(); track run.id) {
          <li class="card">
            <div class="request">
              <strong>{{ run.userId }}</strong>
              <span class="muted">{{ i18n.t('approvals.asked', { date: (run.createdAt | date: 'short' : undefined : i18n.locale()) ?? '' }) }}</span>
              <a [routerLink]="['/runs', run.id]">{{ i18n.t('approvals.trace') }}</a>
            </div>
            <blockquote>{{ run.userInput }}</blockquote>
            <app-approval-card [approvals]="run.pendingApprovals" [busy]="deciding() === run.id" (decide)="decide(run, $event)" />
          </li>
        } @empty {
          <li class="card muted">{{ i18n.t('approvals.empty') }}</li>
        }
      </ol>
    } @else if (queue.isLoading()) {
      <p class="muted">{{ i18n.t('common.loading') }}</p>
    }
  `,
  styles: `
    .queue { list-style: none; margin: 0; padding: 0; display: grid; gap: 1rem; }
    .request { display: flex; flex-wrap: wrap; align-items: baseline; gap: 0.75rem; }
    .request a { margin-left: auto; }
    blockquote { margin: 0.5rem 0 0.75rem; padding-left: 0.75rem; border-left: 3px solid var(--border); white-space: pre-wrap; }
  `,
})
export class ApprovalsPage {
  protected readonly i18n = inject(I18n);
  private readonly api = inject(AgentApi);
  private readonly notifications = inject(Notifications);

  protected readonly queue = httpResource<Run[]>(() => this.api.url('/runs'), { defaultValue: [] });
  /** Run whose decision is being sent; the model keeps working on it, which can take a while. */
  protected readonly deciding = signal<string | null>(null);

  constructor() {
    // Reload when the live count of pending approvals changes (a new request, or another reviewer decided).
    const events = inject(EventsService);
    let previous: number | null | undefined;
    effect(() => {
      const pending = events.pendingApprovals();
      if (previous !== undefined && pending !== previous) {
        untracked(() => this.queue.reload());
      }
      previous = pending;
    });
  }

  protected async decide(run: Run, decision: ApprovalDecision): Promise<void> {
    this.deciding.set(run.id);
    try {
      const result = await firstValueFrom(this.api.decide(run.id, decision));
      const status = this.i18n.t(`status.${result.status}`).toLowerCase();
      this.notifications.info(this.i18n.t(decision.approved ? 'approvals.approved' : 'approvals.rejected', { status }));
      this.queue.reload();
    } catch {
      // Toast shown by the error interceptor.
    } finally {
      this.deciding.set(null);
    }
  }
}
