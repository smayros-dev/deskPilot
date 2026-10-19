import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';

import { ApprovalDecision, PendingApproval } from '../core/models';

interface ApprovalView extends PendingApproval {
  fields: [string, string][];
}

/** Human-in-the-loop gate: shows the tool call the agent wants to make and lets a reviewer decide. */
@Component({
  selector: 'app-approval-card',
  imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="approval" aria-labelledby="approval-title">
      <h3 id="approval-title">{{ readOnly() ? 'Waiting for a reviewer' : 'Approval needed' }}</h3>
      <p>The agent wants to perform an action that needs a human decision:</p>
      @for (call of calls(); track call.toolCallId) {
        <div class="call">
          <code class="tool">{{ call.toolName }}</code>
          <dl>
            @for (field of call.fields; track field[0]) {
              <dt>{{ field[0] }}</dt>
              <dd>{{ field[1] }}</dd>
            }
          </dl>
        </div>
      }
      @if (!readOnly()) {
      <form [formGroup]="form" (ngSubmit)="approve()">
        <label for="comment">Comment for the agent (optional)</label>
        <input id="comment" formControlName="comment" maxlength="1000" placeholder="e.g. Use priority MEDIUM next time" />
        <div class="actions">
          <button type="button" class="button danger" [disabled]="busy()" (click)="reject()">Reject</button>
          <button type="submit" class="button primary" [disabled]="busy() || form.invalid">Approve</button>
        </div>
      </form>
      }
    </section>
  `,
  styles: `
    .approval {
      border: 1px solid var(--warn);
      background: var(--warn-bg);
      border-radius: var(--radius);
      padding: 1rem;
      display: grid;
      gap: 0.75rem;
    }
    h3 { margin: 0; color: var(--warn); }
    p { margin: 0; }
    .call { background: var(--surface); border-radius: var(--radius); padding: 0.75rem; }
    .tool { font-weight: 600; }
    dl { display: grid; grid-template-columns: max-content 1fr; gap: 0.25rem 1rem; margin: 0.5rem 0 0; }
    dt { color: var(--text-muted); }
    dd { margin: 0; overflow-wrap: anywhere; }
    form { display: grid; gap: 0.4rem; }
    .actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 0.25rem; }
  `,
})
export class ApprovalCard {
  readonly approvals = input.required<PendingApproval[]>();
  readonly busy = input(false);
  /** Shows the pending calls without the decision form (users who are not reviewers). */
  readonly readOnly = input(false);
  readonly decide = output<ApprovalDecision>();

  protected readonly form = inject(NonNullableFormBuilder).group({
    comment: ['', Validators.maxLength(1000)],
  });

  protected readonly calls = computed<ApprovalView[]>(() =>
    this.approvals().map((call) => ({ ...call, fields: toFields(call.arguments) })),
  );

  protected approve(): void {
    this.emit(true);
  }

  protected reject(): void {
    this.emit(false);
  }

  private emit(approved: boolean): void {
    const comment = this.form.getRawValue().comment.trim();
    this.decide.emit({ approved, comment: comment || null });
  }
}

function toFields(argumentsJson: string): [string, string][] {
  try {
    const parsed = JSON.parse(argumentsJson) as Record<string, unknown>;
    return Object.entries(parsed).map(([key, value]) => [key, typeof value === 'string' ? value : JSON.stringify(value)]);
  } catch {
    return [['arguments', argumentsJson]];
  }
}
