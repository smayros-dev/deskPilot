import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { RunStatus } from '../core/models';

const LABELS: Record<RunStatus, string> = {
  RUNNING: 'Running',
  AWAITING_APPROVAL: 'Awaiting approval',
  COMPLETED: 'Completed',
  ESCALATED: 'Escalated',
  FAILED: 'Failed',
};

@Component({
  selector: 'app-status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="badge" [attr.data-status]="status()">{{ label() }}</span>`,
  styles: `
    .badge {
      display: inline-block;
      padding: 0.1rem 0.55rem;
      border-radius: 999px;
      font-size: 0.75rem;
      font-weight: 600;
      background: var(--surface-muted);
      color: var(--text-muted);
      white-space: nowrap;
    }
    [data-status='COMPLETED'] { background: var(--ok-bg); color: var(--ok); }
    [data-status='AWAITING_APPROVAL'] { background: var(--warn-bg); color: var(--warn); }
    [data-status='ESCALATED'] { background: var(--info-bg); color: var(--info); }
    [data-status='FAILED'] { background: var(--danger-bg); color: var(--danger); }
  `,
})
export class StatusBadge {
  readonly status = input.required<RunStatus>();
  protected readonly label = computed(() => LABELS[this.status()]);
}
