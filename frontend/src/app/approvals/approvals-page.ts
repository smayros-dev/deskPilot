import { DatePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { ApprovalCard } from '../chat/approval-card';
import { AgentApi } from '../core/agent-api';
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
        <h1>Approvals</h1>
        <p class="muted">Actions the agent wants to take on behalf of users. The run continues as soon as you decide.</p>
      </div>
      <button type="button" class="button" (click)="queue.reload()" [disabled]="queue.isLoading()">Refresh</button>
    </header>

    @if (queue.hasValue()) {
      <ol class="queue">
        @for (run of queue.value(); track run.id) {
          <li class="card">
            <div class="request">
              <strong>{{ run.userId }}</strong>
              <span class="muted">asked {{ run.createdAt | date: 'short' }}</span>
              <a [routerLink]="['/runs', run.id]">Trace</a>
            </div>
            <blockquote>{{ run.userInput }}</blockquote>
            <app-approval-card [approvals]="run.pendingApprovals" [busy]="deciding() === run.id" (decide)="decide(run, $event)" />
          </li>
        } @empty {
          <li class="card muted">Nothing to review. New requests appear here when the agent asks for approval.</li>
        }
      </ol>
    } @else if (queue.isLoading()) {
      <p class="muted">Loading…</p>
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
  private readonly api = inject(AgentApi);
  private readonly notifications = inject(Notifications);

  protected readonly queue = httpResource<Run[]>(() => this.api.url('/runs'), { defaultValue: [] });
  /** Run whose decision is being sent; the model keeps working on it, which can take a while. */
  protected readonly deciding = signal<string | null>(null);

  protected async decide(run: Run, decision: ApprovalDecision): Promise<void> {
    this.deciding.set(run.id);
    try {
      const result = await firstValueFrom(this.api.decide(run.id, decision));
      this.notifications.info(`${decision.approved ? 'Approved' : 'Rejected'}; the run is now ${result.status.toLowerCase().replace('_', ' ')}.`);
      this.queue.reload();
    } catch {
      // Toast shown by the error interceptor.
    } finally {
      this.deciding.set(null);
    }
  }
}
