import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';

import { I18n, TranslationKey } from '../core/i18n';
import { Run, RunStep, StepType } from '../core/models';
import { Answer } from '../shared/answer';
import { DurationPipe } from '../shared/duration-pipe';
import { ExperimentBadge } from '../shared/experiment-badge';
import { SpecialistBadge } from '../shared/specialist-badge';

interface StepView extends RunStep {
  body: string;
  isJson: boolean;
}


/** The ReAct trace of one run: thoughts, tool calls and results, approvals and the answer. */
@Component({
  selector: 'app-run-trace',
  imports: [DecimalPipe, DurationPipe, Answer, SpecialistBadge, ExperimentBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="stats" role="list" [attr.aria-label]="i18n.t('trace.stats')">
      @if (run().model) {
        <span role="listitem"><code>{{ run().model }}</code></span>
      }
      @if (run().specialist; as specialist) {
        <span role="listitem"><app-specialist-badge [specialist]="specialist" /></span>
      }
      @if (run().experiment; as experiment) {
        <span role="listitem"><app-experiment-badge [experiment]="experiment" /></span>
      }
      <span role="listitem"><strong>{{ run().stats.activeMillis | duration: i18n.lang() }}</strong> {{ i18n.t('trace.active') }}</span>
      <span role="listitem"><strong>{{ run().stats.iterations }}</strong> {{ i18n.t('trace.modelCalls') }}</span>
      <span role="listitem"><strong>{{ run().stats.toolCalls }}</strong> {{ i18n.t('trace.toolCalls') }}</span>
      @if (run().stats.toolErrors > 0) {
        <span role="listitem" class="error-text"><strong>{{ run().stats.toolErrors }}</strong> {{ i18n.t('trace.toolErrors') }}</span>
      }
      <span role="listitem"><strong>{{ totalTokens() | number: undefined : i18n.locale() }}</strong> {{ i18n.t('trace.tokens') }}</span>
    </div>

    <ol class="steps">
      @for (step of steps(); track step.id) {
        <li class="step" [attr.data-type]="step.type" [class.error]="step.error">
          <div class="step-head">
            <span class="icon" aria-hidden="true">
              @switch (step.type) {
                @case ('USER_INPUT') { 👤 }
                @case ('THOUGHT') { 💭 }
                @case ('TOOL_CALL') { 🔧 }
                @case ('TOOL_RESULT') { {{ step.error ? '⚠️' : '📄' }} }
                @case ('APPROVAL_REQUESTED') { ✋ }
                @case ('APPROVAL_DECISION') { {{ step.name === 'APPROVED' ? '✅' : '⛔' }} }
                @case ('ANSWER') { 💬 }
                @case ('GUARDRAIL') { 🛡️ }
                @case ('IMAGE_ANALYSIS') { 🖼️ }
                @case ('HANDOFF') { 🧭 }
                @default { ❗ }
              }
            </span>
            <span class="label">{{ i18n.t(stepLabel(step.type)) }}</span>
            @if (step.name && step.type !== 'ANSWER') {
              <code>{{ step.name }}</code>
            }
            @if (step.durationMillis > 0) {
              <span class="muted">{{ step.durationMillis | duration: i18n.lang() }}</span>
            }
          </div>
          @if (step.body) {
            @if (step.type === 'ANSWER') {
              <app-answer class="answer" [text]="step.body" [run]="run()" />
            } @else if (step.isJson) {
              <pre>{{ step.body }}</pre>
            } @else {
              <p>{{ step.body }}</p>
            }
          }
        </li>
      } @empty {
        <li class="muted">{{ i18n.t('trace.noSteps') }}</li>
      }
    </ol>
  `,
  styles: `
    .stats { display: flex; flex-wrap: wrap; gap: 0.5rem 1.25rem; font-size: 0.85rem; color: var(--text-muted); margin-bottom: 0.75rem; }
    .stats strong { color: var(--text); }
    .error-text, .error-text strong { color: var(--danger); }
    .steps { list-style: none; margin: 0; padding: 0; display: grid; gap: 0.5rem; }
    .step { border-inline-start: 3px solid var(--border); padding-block: 0.25rem; padding-inline: 0.75rem 0; }
    .step[data-type='TOOL_CALL'], .step[data-type='TOOL_RESULT'] { border-inline-start-color: var(--accent); }
    .step[data-type='APPROVAL_REQUESTED'], .step[data-type='APPROVAL_DECISION'] { border-inline-start-color: var(--warn); }
    .step[data-type='ANSWER'] { border-inline-start-color: var(--ok); }
    .step[data-type='HANDOFF'] { border-inline-start-color: var(--accent); }
    .step.error, .step[data-type='GUARDRAIL'] { border-inline-start-color: var(--danger); }
    .step-head { display: flex; flex-wrap: wrap; align-items: center; gap: 0.5rem; font-size: 0.85rem; }
    .label { font-weight: 600; }
    .muted { color: var(--text-muted); font-size: 0.8rem; }
    p { margin: 0.25rem 0 0; white-space: pre-wrap; overflow-wrap: anywhere; }
    .answer { margin-top: 0.25rem; }
    pre { margin: 0.25rem 0 0; max-height: 16rem; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 0.78rem; background: var(--surface-muted); padding: 0.5rem; border-radius: var(--radius); }
  `,
})
export class RunTrace {
  readonly run = input.required<Run>();
  protected readonly i18n = inject(I18n);

  protected stepLabel(type: StepType): TranslationKey {
    return `step.${type}`;
  }

  protected readonly totalTokens = computed(() => this.run().stats.promptTokens + this.run().stats.completionTokens);

  protected readonly steps = computed<StepView[]>(() =>
    this.run().steps.map((step) => {
      const pretty = prettyJson(step.content);
      return { ...step, body: pretty ?? step.content ?? '', isJson: pretty !== null };
    }),
  );
}

function prettyJson(text: string | null): string | null {
  if (!text || !/^\s*[[{]/.test(text)) {
    return null;
  }
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return null;
  }
}
