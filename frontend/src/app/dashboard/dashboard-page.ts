import { DatePipe, DecimalPipe, PercentPipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { RouterLink } from '@angular/router';

import { AgentApi } from '../core/agent-api';
import { AuthService } from '../core/auth';
import { AgentMetrics, RatedAnswer } from '../core/models';
import { DurationPipe } from '../shared/duration-pipe';

interface StatusSlice {
  key: string;
  label: string;
  count: number;
  share: number;
}

/** Success metrics from step 10 of the brief: task success, latency, tool accuracy, escalations. */
@Component({
  selector: 'app-dashboard-page',
  imports: [DatePipe, DecimalPipe, PercentPipe, DurationPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dashboard-page.html',
  styleUrl: './dashboard-page.css',
})
export class DashboardPage {
  private readonly api = inject(AgentApi);

  private readonly auth = inject(AuthService);

  protected readonly metrics = httpResource<AgentMetrics>(() => this.api.url('/metrics'));
  /** Latest 👎 with their comments; reviewers and admins only (the request is not even made for others). */
  protected readonly canReadFeedback = computed(() => this.auth.isReviewer() || this.auth.isAdmin());
  protected readonly negativeFeedback = httpResource<RatedAnswer[]>(
    () => (this.canReadFeedback() ? this.api.url('/feedback?rating=DOWN') : undefined),
    { defaultValue: [] },
  );

  protected readonly statusSlices = computed<StatusSlice[]>(() => {
    if (!this.metrics.hasValue()) {
      return [];
    }
    const m = this.metrics.value();
    const total = Math.max(1, m.totalRuns);
    return [
      { key: 'completed', label: 'Completed', count: m.completed },
      { key: 'escalated', label: 'Escalated', count: m.escalated },
      { key: 'failed', label: 'Failed', count: m.failed },
      { key: 'awaiting', label: 'Awaiting approval', count: m.awaitingApproval },
    ].map((slice) => ({ ...slice, share: slice.count / total }));
  });

  protected readonly maxToolCalls = computed(() =>
    this.metrics.hasValue() ? Math.max(1, ...this.metrics.value().tools.map((t) => t.calls)) : 1,
  );

  protected refresh(): void {
    this.metrics.reload();
    this.negativeFeedback.reload();
  }
}
