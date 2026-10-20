import { DatePipe, JsonPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, input, linkedSignal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService } from '../core/auth';
import { I18n } from '../core/i18n';
import { Run, RunFeedback } from '../core/models';
import { Answer } from '../shared/answer';
import { FeedbackButtons } from '../shared/feedback-buttons';
import { StatusBadge } from '../shared/status-badge';
import { RunTrace } from './run-trace';

/** Full trace of one run. `run` comes from the route resolver via component input binding. */
@Component({
  selector: 'app-run-page',
  imports: [RouterLink, DatePipe, JsonPipe, RunTrace, StatusBadge, Answer, FeedbackButtons],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let r = run();
    <a routerLink="/chat" class="back">{{ i18n.t('run.back') }}</a>
    <section class="card">
      <header class="run-header">
        <div>
          <h1>{{ r.userInput }}</h1>
          <p class="muted">
            {{ i18n.t('run.run') }} <code>{{ r.id }}</code> · {{ i18n.t('run.started', { date: (r.createdAt | date: 'medium' : undefined : i18n.locale()) ?? '' }) }}
            @if (r.completedAt) {
              · {{ i18n.t('run.finished', { time: (r.completedAt | date: 'mediumTime' : undefined : i18n.locale()) ?? '' }) }}
            }
          </p>
        </div>
        <app-status-badge [status]="r.status" />
      </header>

      @if (r.answer) {
        <blockquote><app-answer [text]="r.answer" [run]="r" /></blockquote>
      }
      @if (r.status === 'COMPLETED' || r.status === 'ESCALATED' || r.status === 'FAILED') {
        @if (r.userId === auth.username()) {
          <div class="rate"><app-feedback-buttons [runId]="r.id" [(feedback)]="feedback" /></div>
        } @else if (r.feedback; as given) {
          <p class="muted rate">
            {{ i18n.t('run.ratedBy', { rating: given.rating === 'UP' ? '👍' : '👎', user: r.userId }) }}
            @if (given.comment) { · <q>{{ given.comment }}</q> }
          </p>
        }
      }

      <h2>{{ i18n.t('run.reasoningTrace') }}</h2>
      <app-run-trace [run]="r" />

      <details>
        <summary>{{ i18n.t('run.rawJson') }}</summary>
        <pre>{{ r | json }}</pre>
      </details>
    </section>
  `,
  styles: `
    .back { display: inline-block; margin-bottom: 1rem; }
    .run-header { display: flex; justify-content: space-between; align-items: start; gap: 1rem; }
    h1 { font-size: 1.2rem; margin: 0 0 0.25rem; overflow-wrap: anywhere; }
    h2 { font-size: 1rem; margin: 1.5rem 0 0.75rem; }
    blockquote { margin: 1rem 0 0; padding: 0.75rem 1rem; border-left: 4px solid var(--ok); background: var(--surface-muted); border-radius: var(--radius); white-space: pre-wrap; }
    .rate { margin: 0.5rem 0 0; }
    details { margin-top: 1.5rem; }
    pre { max-height: 24rem; overflow: auto; font-size: 0.78rem; background: var(--surface-muted); padding: 0.75rem; border-radius: var(--radius); }
  `,
})
export class RunPage {
  protected readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthService);

  readonly run = input.required<Run>();
  /** Only the user who asked can rate the answer; starts from the saved rating. */
  protected readonly feedback = linkedSignal<RunFeedback | null>(() => this.run().feedback ?? null);
}
