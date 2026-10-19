import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { Run, RunStep, StepType } from '../core/models';
import { DurationPipe } from '../shared/duration-pipe';

interface StepView extends RunStep {
  label: string;
  body: string;
  isJson: boolean;
}

const STEP_LABELS: Record<StepType, string> = {
  USER_INPUT: 'User',
  THOUGHT: 'Thought',
  TOOL_CALL: 'Tool call',
  TOOL_RESULT: 'Tool result',
  APPROVAL_REQUESTED: 'Approval requested',
  APPROVAL_DECISION: 'Human decision',
  ANSWER: 'Answer',
  GUARDRAIL: 'Guardrail',
  ERROR: 'Error',
};

/** The ReAct trace of one run: thoughts, tool calls and results, approvals and the answer. */
@Component({
  selector: 'app-run-trace',
  imports: [DecimalPipe, DurationPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="stats" role="list" aria-label="Run statistics">
      @if (run().model) {
        <span role="listitem"><code>{{ run().model }}</code></span>
      }
      <span role="listitem"><strong>{{ run().stats.activeMillis | duration }}</strong> active</span>
      <span role="listitem"><strong>{{ run().stats.iterations }}</strong> model calls</span>
      <span role="listitem"><strong>{{ run().stats.toolCalls }}</strong> tool calls</span>
      @if (run().stats.toolErrors > 0) {
        <span role="listitem" class="error-text"><strong>{{ run().stats.toolErrors }}</strong> tool errors</span>
      }
      <span role="listitem"><strong>{{ totalTokens() | number }}</strong> tokens</span>
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
                @default { ❗ }
              }
            </span>
            <span class="label">{{ step.label }}</span>
            @if (step.name && step.type !== 'ANSWER') {
              <code>{{ step.name }}</code>
            }
            @if (step.durationMillis > 0) {
              <span class="muted">{{ step.durationMillis | duration }}</span>
            }
          </div>
          @if (step.body) {
            @if (step.isJson) {
              <pre>{{ step.body }}</pre>
            } @else {
              <p>{{ step.body }}</p>
            }
          }
        </li>
      } @empty {
        <li class="muted">No steps recorded.</li>
      }
    </ol>
  `,
  styles: `
    .stats { display: flex; flex-wrap: wrap; gap: 0.5rem 1.25rem; font-size: 0.85rem; color: var(--text-muted); margin-bottom: 0.75rem; }
    .stats strong { color: var(--text); }
    .error-text, .error-text strong { color: var(--danger); }
    .steps { list-style: none; margin: 0; padding: 0; display: grid; gap: 0.5rem; }
    .step { border-left: 3px solid var(--border); padding: 0.25rem 0 0.25rem 0.75rem; }
    .step[data-type='TOOL_CALL'], .step[data-type='TOOL_RESULT'] { border-left-color: var(--accent); }
    .step[data-type='APPROVAL_REQUESTED'], .step[data-type='APPROVAL_DECISION'] { border-left-color: var(--warn); }
    .step[data-type='ANSWER'] { border-left-color: var(--ok); }
    .step.error, .step[data-type='GUARDRAIL'] { border-left-color: var(--danger); }
    .step-head { display: flex; flex-wrap: wrap; align-items: center; gap: 0.5rem; font-size: 0.85rem; }
    .label { font-weight: 600; }
    .muted { color: var(--text-muted); font-size: 0.8rem; }
    p { margin: 0.25rem 0 0; white-space: pre-wrap; overflow-wrap: anywhere; }
    pre { margin: 0.25rem 0 0; max-height: 16rem; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 0.78rem; background: var(--surface-muted); padding: 0.5rem; border-radius: var(--radius); }
  `,
})
export class RunTrace {
  readonly run = input.required<Run>();

  protected readonly totalTokens = computed(() => this.run().stats.promptTokens + this.run().stats.completionTokens);

  protected readonly steps = computed<StepView[]>(() =>
    this.run().steps.map((step) => {
      const pretty = prettyJson(step.content);
      return { ...step, label: STEP_LABELS[step.type], body: pretty ?? step.content ?? '', isJson: pretty !== null };
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
