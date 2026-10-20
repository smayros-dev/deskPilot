import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';

import { I18n } from '../core/i18n';
import { RunStatus } from '../core/models';


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
  private readonly i18n = inject(I18n);
  protected readonly label = computed(() => this.i18n.t(`status.${this.status()}`));
}
