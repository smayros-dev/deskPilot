import { DecimalPipe, PercentPipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { AgentApi } from '../core/agent-api';
import { AgentMetrics } from '../core/models';
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
  imports: [DecimalPipe, PercentPipe, DurationPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dashboard-page.html',
  styleUrl: './dashboard-page.css',
})
export class DashboardPage {
  private readonly api = inject(AgentApi);

  protected readonly metrics = httpResource<AgentMetrics>(() => this.api.url('/metrics'));

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
  }
}
