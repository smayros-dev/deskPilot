import { ChangeDetectionStrategy, Component, inject, input, linkedSignal, model, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { AgentApi } from '../core/agent-api';
import { I18n } from '../core/i18n';
import { FeedbackRating, RunFeedback } from '../core/models';

/**
 * 👍 / 👎 under a finished answer, with an optional comment after 👎. Two-way bound (`[(feedback)]`): the change is
 * shown at once and rolled back if the backend refuses it. Clicking the selected button again removes the rating.
 */
@Component({
  selector: 'app-feedback-buttons',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="feedback" role="group" [attr.aria-label]="i18n.t('feedback.group')">
      <button type="button" class="vote" [title]="i18n.t('feedback.helpful')" [attr.aria-label]="i18n.t('feedback.helpful')"
              [attr.aria-pressed]="feedback()?.rating === 'UP'" [disabled]="saving()" (click)="rate('UP')">👍</button>
      <button type="button" class="vote" [title]="i18n.t('feedback.notHelpful')" [attr.aria-label]="i18n.t('feedback.notHelpful')"
              [attr.aria-pressed]="feedback()?.rating === 'DOWN'" [disabled]="saving()" (click)="rate('DOWN')">👎</button>
      @if (commenting()) {
        <form class="comment-form" (submit)="$event.preventDefault(); sendComment()">
          <input type="text" maxlength="1000" [placeholder]="i18n.t('feedback.placeholder')" [attr.aria-label]="i18n.t('feedback.whatWrong')"
                 [value]="comment()" (input)="comment.set($any($event.target).value)" />
          <button type="submit" class="link-button" [disabled]="saving()">{{ i18n.t('common.send') }}</button>
          <button type="button" class="link-button" (click)="commenting.set(false)">{{ i18n.t('feedback.skip') }}</button>
        </form>
      } @else if (feedback(); as given) {
        <span class="thanks">
          {{ i18n.t('feedback.thanks') }}
          @if (given.comment) { · <q>{{ given.comment }}</q> }
        </span>
      }
    </div>
  `,
  styles: `
    .feedback { display: flex; flex-wrap: wrap; align-items: center; gap: 0.35rem; font-size: 0.8rem; }
    .vote {
      border: 1px solid var(--border);
      background: var(--surface);
      border-radius: 999px;
      padding: 0.1rem 0.5rem;
      cursor: pointer;
      line-height: 1.4;
      filter: grayscale(1);
      opacity: 0.75;
    }
    .vote:hover:not(:disabled), .vote[aria-pressed='true'] { filter: none; opacity: 1; }
    .vote[aria-pressed='true'] { border-color: var(--accent); background: var(--surface-muted); }
    .vote:disabled { cursor: progress; }
    .comment-form { display: flex; flex-wrap: wrap; align-items: center; gap: 0.4rem; flex: 1; min-width: 12rem; }
    .comment-form input { flex: 1; min-width: 8rem; font-size: 0.8rem; padding: 0.2rem 0.4rem; }
    .thanks { color: var(--text-muted); overflow-wrap: anywhere; }
  `,
})
export class FeedbackButtons {
  protected readonly i18n = inject(I18n);
  private readonly api = inject(AgentApi);

  readonly runId = input.required<string>();
  readonly feedback = model<RunFeedback | null>(null);

  protected readonly saving = signal(false);
  protected readonly commenting = signal(false);
  protected readonly comment = linkedSignal(() => this.feedback()?.comment ?? '');

  protected async rate(rating: FeedbackRating): Promise<void> {
    if (this.feedback()?.rating === rating) {
      this.commenting.set(false);
      await this.save(null);
      return;
    }
    // A new 👍 drops the comment of an earlier 👎; a 👎 opens the comment box.
    this.commenting.set(rating === 'DOWN');
    await this.save({ rating, comment: rating === 'DOWN' ? (this.feedback()?.comment ?? null) : null });
  }

  protected async sendComment(): Promise<void> {
    this.commenting.set(false);
    await this.save({ rating: 'DOWN', comment: this.comment().trim() || null });
  }

  /** Optimistic update: shows the change, then replaces it with the saved rating, or restores the previous one. */
  private async save(next: { rating: FeedbackRating; comment: string | null } | null): Promise<void> {
    const previous = this.feedback();
    this.feedback.set(next ? { ...next, updatedAt: new Date().toISOString() } : null);
    this.saving.set(true);
    try {
      if (next) {
        this.feedback.set(await firstValueFrom(this.api.rateRun(this.runId(), next.rating, next.comment)));
      } else {
        await firstValueFrom(this.api.removeRating(this.runId()));
      }
    } catch {
      this.feedback.set(previous);
      this.commenting.set(false);
    } finally {
      this.saving.set(false);
    }
  }
}
