import { DatePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { I18n, TranslationKey } from '../core/i18n';
import { Notifications } from '../core/notifications';
import { GapDraft, GapReason, KnowledgeGap, KnowledgeGapStatus, KnowledgeGapsApi } from './knowledge-gaps-api';

/**
 * Reviewers see what the knowledge base does not answer (12d): unanswered questions grouped by topic. For each gap
 * the model drafts an article (or the reviewer writes it), the reviewer edits it and publishes it into the knowledge
 * base, or dismisses the gap.
 */
@Component({
  selector: 'app-knowledge-gaps-panel',
  imports: [DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card" aria-labelledby="gaps-title">
      <div class="head">
        <h2 id="gaps-title">{{ i18n.t('gaps.title') }}</h2>
        <button type="button" class="button" [disabled]="busy() !== null" (click)="detect()">
          {{ busy() === 'detect' ? i18n.t('gaps.detecting') : i18n.t('gaps.detect') }}
        </button>
      </div>
      <p class="muted hint">{{ i18n.t('gaps.intro') }}</p>

      @for (gap of visible(); track gap.id) {
        <article class="gap" [attr.data-status]="gap.status">
          <header>
            <h3>“{{ gap.title }}”</h3>
            <span class="count">{{ i18n.t('gaps.questions', { count: gap.questionCount }) }}</span>
            <span class="status" [attr.data-status]="gap.status">{{ i18n.t(statusLabel(gap.status)) }}</span>
          </header>
          <ul class="reasons" [attr.aria-label]="i18n.t('gaps.reasons')">
            @for (reason of reasons(gap); track reason.reason) {
              <li>{{ i18n.t(reasonLabel(reason.reason)) }} × {{ reason.count }}</li>
            }
          </ul>
          <details>
            <summary>{{ i18n.t('gaps.samples') }}</summary>
            <ul class="samples">
              @for (q of gap.questions; track q.runId) {
                <li>{{ q.question }} <small class="muted">· {{ q.askedAt | date: 'short' : undefined : i18n.locale() }}</small></li>
              }
            </ul>
          </details>

          @if (editing()?.id === gap.id) {
            <form class="editor" (submit)="$event.preventDefault(); save(gap)">
              <label>
                <span>{{ i18n.t('gaps.articleTitle') }}</span>
                <input type="text" maxlength="200" required [value]="editing()!.title" (input)="edit('title', $any($event.target).value)" />
              </label>
              <label>
                <span>{{ i18n.t('gaps.tags') }}</span>
                <input type="text" maxlength="300" [value]="editing()!.tags" (input)="edit('tags', $any($event.target).value)" />
              </label>
              <label>
                <span>{{ i18n.t('gaps.content') }}</span>
                <textarea rows="10" required [value]="editing()!.content" (input)="edit('content', $any($event.target).value)"></textarea>
              </label>
              <div class="actions">
                <button type="submit" class="button primary" [disabled]="busy() !== null || !canSave()">{{ i18n.t('gaps.saveDraft') }}</button>
                <button type="button" class="link-button" (click)="editing.set(null)">{{ i18n.t('common.cancel') }}</button>
              </div>
            </form>
          } @else if (gap.status === 'DRAFTED') {
            <div class="draft">
              <p class="notice">{{ i18n.t('gaps.reviewNotice', { by: gap.draftedBy ?? '' }) }}</p>
              <h4>{{ gap.draftTitle }}</h4>
              @if (gap.draftTags) {
                <p class="muted tags">{{ gap.draftTags }}</p>
              }
              <p class="content">{{ gap.draftContent }}</p>
            </div>
          } @else if (gap.status === 'PUBLISHED') {
            <p class="decided">
              {{ i18n.t('gaps.publishedBy', { by: gap.decidedBy ?? '', id: gap.articleId ?? '' }) }}
              {{ gap.decidedAt | date: 'short' : undefined : i18n.locale() }}
            </p>
          } @else if (gap.status === 'DISMISSED') {
            <p class="decided">{{ i18n.t('gaps.dismissedBy', { by: gap.decidedBy ?? '' }) }} {{ gap.decidedAt | date: 'short' : undefined : i18n.locale() }}</p>
          }

          @if (pending(gap.status) && editing()?.id !== gap.id) {
            <div class="actions">
              @if (busy() === 'draft-' + gap.id) {
                <span role="status" class="muted">{{ i18n.t('gaps.drafting') }}</span>
                <progress [attr.aria-label]="i18n.t('gaps.drafting')"></progress>
              } @else {
                @if (gap.status === 'DRAFTED') {
                  <button type="button" class="button primary" [disabled]="busy() !== null" (click)="publish(gap)">{{ i18n.t('gaps.publish') }}</button>
                  <button type="button" class="button" [disabled]="busy() !== null" (click)="startEditing(gap)">{{ i18n.t('gaps.edit') }}</button>
                }
                <button type="button" class="button" [disabled]="busy() !== null" (click)="draft(gap)">
                  {{ gap.status === 'DRAFTED' ? i18n.t('gaps.redraft') : i18n.t('gaps.draft') }}
                </button>
                @if (gap.status === 'OPEN') {
                  <button type="button" class="link-button" [disabled]="busy() !== null" (click)="startEditing(gap)">{{ i18n.t('gaps.writeMyself') }}</button>
                }
                <button type="button" class="link-button danger" [disabled]="busy() !== null" (click)="dismiss(gap)">{{ i18n.t('gaps.dismiss') }}</button>
              }
            </div>
          }
        </article>
      } @empty {
        @if (gaps.hasValue()) {
          <p class="muted">{{ i18n.t('gaps.none') }}</p>
        }
      }

      @if (decidedCount() > 0) {
        <label class="toggle">
          <input type="checkbox" [checked]="showDecided()" (change)="showDecided.set($any($event.target).checked)" />
          {{ i18n.t('gaps.showDecided', { count: decidedCount() }) }}
        </label>
      }
    </section>
  `,
  styles: `
    .head { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 0.5rem; }
    h2 { font-size: 1rem; margin: 0; }
    .hint { font-size: 0.85rem; margin: 0.4rem 0 0.75rem; }
    .gap { border: 1px solid var(--border); border-radius: var(--radius); padding: 0.75rem; margin-block: 0.75rem; display: grid; gap: 0.5rem; }
    .gap[data-status='PUBLISHED'], .gap[data-status='DISMISSED'] { opacity: 0.75; }
    .gap header { display: flex; flex-wrap: wrap; align-items: baseline; gap: 0.5rem; }
    h3 { font-size: 0.95rem; margin: 0; flex: 1; min-width: 12rem; }
    h4 { margin: 0; font-size: 0.95rem; }
    .count { font-size: 0.8rem; font-weight: 600; }
    .status { font-size: 0.75rem; padding: 0.05rem 0.5rem; border-radius: 999px; background: var(--accent-bg); color: var(--accent); }
    .status[data-status='PUBLISHED'] { color: var(--ok); }
    .status[data-status='DISMISSED'] { color: var(--text-muted); }
    .reasons { list-style: none; padding: 0; margin: 0; display: flex; flex-wrap: wrap; gap: 0.3rem; font-size: 0.75rem; }
    .reasons li { padding: 0.1rem 0.5rem; border: 1px solid var(--border); border-radius: 999px; }
    summary { cursor: pointer; font-size: 0.85rem; }
    .samples { margin: 0.4rem 0 0; padding-inline-start: 1.2rem; font-size: 0.85rem; }
    .draft { display: grid; gap: 0.35rem; }
    .notice { margin: 0; font-size: 0.8rem; }
    .content { margin: 0; white-space: pre-line; font-size: 0.9rem; line-height: 1.5; }
    .tags { margin: 0; font-size: 0.8rem; }
    .decided { margin: 0; font-size: 0.85rem; color: var(--text-muted); }
    .editor { display: grid; gap: 0.5rem; }
    .editor label { display: grid; gap: 0.25rem; }
    textarea { width: 100%; font: inherit; }
    .actions { display: flex; flex-wrap: wrap; align-items: center; gap: 0.5rem; }
    .actions progress { flex: 1; max-width: 12rem; accent-color: var(--accent); }
    .danger { color: var(--danger); }
    .toggle { display: flex; align-items: center; gap: 0.4rem; font-size: 0.85rem; }
  `,
})
export class KnowledgeGapsPanel {
  protected readonly i18n = inject(I18n);
  private readonly api = inject(KnowledgeGapsApi);
  private readonly notifications = inject(Notifications);

  /** A gap was published: the knowledge base has a new article. */
  readonly published = output<void>();

  protected readonly gaps = httpResource<KnowledgeGap[]>(() => this.api.baseUrl, { defaultValue: [] });
  protected readonly showDecided = signal(false);
  /** What is running: 'detect', 'draft-<id>' or 'gap-<id>'. */
  protected readonly busy = signal<string | null>(null);
  protected readonly editing = signal<(GapDraft & { id: number }) | null>(null);

  protected readonly decidedCount = computed(() => this.gaps.value().filter((g) => !this.pending(g.status)).length);
  protected readonly visible = computed(() =>
    this.showDecided() ? this.gaps.value() : this.gaps.value().filter((g) => this.pending(g.status)),
  );
  protected readonly canSave = computed(() => {
    const draft = this.editing();
    return !!draft && draft.title.trim().length > 0 && draft.content.trim().length > 0;
  });

  protected pending(status: KnowledgeGapStatus): boolean {
    return status === 'OPEN' || status === 'DRAFTED';
  }

  protected statusLabel(status: KnowledgeGapStatus): TranslationKey {
    return `gapStatus.${status}`;
  }

  protected reasonLabel(reason: GapReason): TranslationKey {
    return `gapReason.${reason}`;
  }

  protected reasons(gap: KnowledgeGap): { reason: GapReason; count: number }[] {
    return (Object.entries(gap.reasons) as [GapReason, number][]).map(([reason, count]) => ({ reason, count }));
  }

  protected async detect(): Promise<void> {
    await this.run('detect', async () => {
      const result = await firstValueFrom(this.api.detect());
      this.notifications.info(this.i18n.t('gaps.detected', { newGaps: result.newGaps, grown: result.grownGaps, candidates: result.candidates }));
    });
  }

  protected async draft(gap: KnowledgeGap): Promise<void> {
    await this.run(`draft-${gap.id}`, async () => {
      await firstValueFrom(this.api.draft(gap.id));
      this.notifications.info(this.i18n.t('gaps.drafted'));
    });
  }

  protected startEditing(gap: KnowledgeGap): void {
    this.editing.set({
      id: gap.id,
      title: gap.draftTitle ?? gap.title,
      tags: gap.draftTags ?? '',
      content: gap.draftContent ?? '',
    });
  }

  protected edit(field: keyof GapDraft, value: string): void {
    this.editing.update((draft) => (draft ? { ...draft, [field]: value } : draft));
  }

  protected async save(gap: KnowledgeGap): Promise<void> {
    const draft = this.editing();
    if (!draft || !this.canSave()) {
      return;
    }
    await this.run(`gap-${gap.id}`, async () => {
      await firstValueFrom(this.api.saveDraft(gap.id, { title: draft.title.trim(), content: draft.content.trim(), tags: draft.tags.trim() }));
      this.editing.set(null);
    });
  }

  protected async publish(gap: KnowledgeGap): Promise<void> {
    if (!confirm(this.i18n.t('gaps.confirmPublish', { title: gap.draftTitle ?? gap.title }))) {
      return;
    }
    await this.run(`gap-${gap.id}`, async () => {
      await firstValueFrom(this.api.publish(gap.id));
      this.notifications.info(this.i18n.t('gaps.publishedToast', { title: gap.draftTitle ?? gap.title }));
      this.published.emit();
    });
  }

  protected async dismiss(gap: KnowledgeGap): Promise<void> {
    if (!confirm(this.i18n.t('gaps.confirmDismiss', { title: gap.title }))) {
      return;
    }
    await this.run(`gap-${gap.id}`, () => firstValueFrom(this.api.dismiss(gap.id)));
  }

  private async run(task: string, action: () => Promise<unknown>): Promise<void> {
    this.busy.set(task);
    try {
      await action();
      this.gaps.reload();
    } catch {
      // Toast shown by the error interceptor.
    } finally {
      this.busy.set(null);
    }
  }
}
