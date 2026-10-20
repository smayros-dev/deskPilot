import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';

import { I18n } from '../core/i18n';
import { RunExperiment } from '../core/models';

/** Badge naming the A/B experiment and variant of a run (12m); the API only sends it to reviewers and admins. */
@Component({
  selector: 'app-experiment-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="badge" [title]="i18n.t('experiment.badgeTitle', { name: experiment().name ?? '', variant: experiment().variant })"
    >🧪 {{ i18n.t('experiment.badge', { variant: experiment().variant }) }}</span>`,
  styles: `
    .badge { display: inline-flex; align-items: center; gap: 0.25rem; padding: 0.05rem 0.5rem; border-radius: 999px;
             border: 1px solid var(--border); background: var(--surface-muted); font-size: 0.75rem; white-space: nowrap; }
  `,
})
export class ExperimentBadge {
  protected readonly i18n = inject(I18n);
  readonly experiment = input.required<RunExperiment>();
}
