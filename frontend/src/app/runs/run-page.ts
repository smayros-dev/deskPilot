import { DatePipe, JsonPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Run } from '../core/models';
import { Answer } from '../shared/answer';
import { StatusBadge } from '../shared/status-badge';
import { RunTrace } from './run-trace';

/** Full trace of one run. `run` comes from the route resolver via component input binding. */
@Component({
  selector: 'app-run-page',
  imports: [RouterLink, DatePipe, JsonPipe, RunTrace, StatusBadge, Answer],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let r = run();
    <a routerLink="/chat" class="back">← Back to chat</a>
    <section class="card">
      <header class="run-header">
        <div>
          <h1>{{ r.userInput }}</h1>
          <p class="muted">
            Run <code>{{ r.id }}</code> · started {{ r.createdAt | date: 'medium' }}
            @if (r.completedAt) {
              · finished {{ r.completedAt | date: 'mediumTime' }}
            }
          </p>
        </div>
        <app-status-badge [status]="r.status" />
      </header>

      @if (r.answer) {
        <blockquote><app-answer [text]="r.answer" [run]="r" /></blockquote>
      }

      <h2>Reasoning trace</h2>
      <app-run-trace [run]="r" />

      <details>
        <summary>Raw JSON</summary>
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
    details { margin-top: 1.5rem; }
    pre { max-height: 24rem; overflow: auto; font-size: 0.78rem; background: var(--surface-muted); padding: 0.75rem; border-radius: var(--radius); }
  `,
})
export class RunPage {
  readonly run = input.required<Run>();
}
