import { DatePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, effect, inject, signal, untracked } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { debounceTime, distinctUntilChanged, firstValueFrom } from 'rxjs';

import { AgentApi } from '../core/agent-api';
import { ChatStore } from '../core/chat-store';
import { I18n } from '../core/i18n';
import { Session } from '../core/models';

/** Delay before a search is sent, so typing does not fire a request per key. */
export const SEARCH_DEBOUNCE_MS = 300;

/** The user's past conversations: search, reopen, rename. */
@Component({
  selector: 'app-conversation-list',
  imports: [DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2>{{ i18n.t('conversations.title') }}</h2>
    <label for="conversation-search" class="visually-hidden">{{ i18n.t('conversations.search') }}</label>
    <input id="conversation-search" type="search" [placeholder]="i18n.t('conversations.searchPlaceholder')" [value]="search()"
           (input)="search.set($any($event.target).value)" />

    <ul class="conversations">
      @for (conversation of conversations.value(); track conversation.id) {
        <li [class.current]="conversation.id === store.session()?.id">
          @if (editing() === conversation.id) {
            <input class="rename" [value]="conversation.title ?? ''" maxlength="200" [attr.aria-label]="i18n.t('conversations.titleLabel')"
                   (keydown.enter)="rename(conversation, $any($event.target).value)"
                   (keydown.escape)="editing.set(null)" (blur)="editing.set(null)" />
          } @else {
            <button type="button" class="open" [disabled]="store.sending()" (click)="open(conversation)"
                    [attr.aria-current]="conversation.id === store.session()?.id ? 'true' : null">
              <span class="title">{{ conversation.title ?? i18n.t('conversations.untitled') }}</span>
              <span class="meta">{{ conversation.lastActivityAt | date: 'short' : undefined : i18n.locale() }} · {{ conversation.runs === 1 ? i18n.t('conversations.one') : i18n.t('conversations.many', { count: conversation.runs }) }}</span>
            </button>
            <button type="button" class="link-button edit" [attr.aria-label]="i18n.t('conversations.rename', { title: conversation.title ?? i18n.t('conversations.untitled') })"
                    (click)="editing.set(conversation.id)">✏️</button>
            <button type="button" class="link-button edit" [attr.aria-label]="i18n.t('conversations.delete', { title: conversation.title ?? i18n.t('conversations.untitled') })"
                    [disabled]="store.sending()" (click)="remove(conversation)">🗑️</button>
          }
        </li>
      } @empty {
        <li class="muted">{{ search() ? i18n.t('conversations.noMatch') : i18n.t('conversations.empty') }}</li>
      }
    </ul>
  `,
  styles: `
    h2 { font-size: 1rem; margin: 0 0 0.5rem; }
    input[type='search'] { width: 100%; margin-bottom: 0.5rem; }
    .conversations { list-style: none; margin: 0; padding: 0; display: grid; gap: 0.15rem; max-height: 16rem; overflow-y: auto; }
    li { display: flex; align-items: center; gap: 0.25rem; border-radius: var(--radius); }
    li.current { background: var(--surface-muted); }
    .open { flex: 1; min-width: 0; display: grid; text-align: start; background: none; border: none; padding: 0.35rem 0.4rem; cursor: pointer; color: inherit; font: inherit; }
    .open:hover:not(:disabled) { background: var(--surface-muted); border-radius: var(--radius); }
    .title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 0.88rem; }
    .meta { font-size: 0.72rem; color: var(--text-muted); }
    .edit { opacity: 0.6; font-size: 0.8rem; }
    .rename { flex: 1; font-size: 0.88rem; }
  `,
})
export class ConversationList {
  private readonly api = inject(AgentApi);
  protected readonly store = inject(ChatStore);
  protected readonly i18n = inject(I18n);

  protected readonly search = signal('');
  private readonly query = toSignal(toObservable(this.search).pipe(debounceTime(SEARCH_DEBOUNCE_MS), distinctUntilChanged()), {
    initialValue: '',
  });
  protected readonly conversations = httpResource<Session[]>(
    () => this.api.url(`/sessions?q=${encodeURIComponent(this.query().trim())}`),
    { defaultValue: [] },
  );
  protected readonly editing = signal<string | null>(null);

  constructor() {
    // A new message or a finished run changes titles and order: refresh the list.
    let first = true;
    effect(() => {
      this.store.lastRun()?.status;
      this.store.session()?.id;
      if (first) {
        first = false;
        return;
      }
      untracked(() => this.conversations.reload());
    });
  }

  protected open(conversation: Session): void {
    if (conversation.id !== this.store.session()?.id) {
      void this.store.open(conversation.id);
    }
  }

  /** Deletes the conversation and its messages for good (privacy); the current one is replaced by a new one. */
  protected async remove(conversation: Session): Promise<void> {
    if (!confirm(this.i18n.t('conversations.confirmDelete', { title: conversation.title ?? this.i18n.t('conversations.thisOne') }))) {
      return;
    }
    try {
      await firstValueFrom(this.api.deleteSession(conversation.id));
      if (conversation.id === this.store.session()?.id) {
        await this.store.newConversation();
      }
      this.conversations.reload();
    } catch {
      // Toast shown by the error interceptor (e.g. 409 while the agent is working on it).
    }
  }

  protected async rename(conversation: Session, title: string): Promise<void> {
    this.editing.set(null);
    const trimmed = title.trim();
    if (!trimmed || trimmed === conversation.title) {
      return;
    }
    try {
      await firstValueFrom(this.api.renameSession(conversation.id, trimmed));
      this.conversations.reload();
    } catch {
      // Toast shown by the error interceptor.
    }
  }
}
