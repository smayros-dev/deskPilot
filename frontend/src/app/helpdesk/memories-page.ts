import { DatePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { AgentApi } from '../core/agent-api';
import { ChatStore } from '../core/chat-store';
import { saveBlob } from '../core/download';
import { Memory } from '../core/models';
import { Notifications } from '../core/notifications';
import { PrivacyApi } from './privacy-api';

/** Long-term memory of the signed-in user, and their privacy controls (export or delete their data). */
@Component({
  selector: 'app-memories-page',
  imports: [DatePipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="page-header">
      <div>
        <h1>Your memories</h1>
        <p class="muted">Facts the agent saved with <code>save_memory</code> and recalls in later conversations.</p>
      </div>
      @if (memories.hasValue() && memories.value().length > 0) {
        <button type="button" class="button danger" [disabled]="busy()" (click)="deleteAllMemories()">Forget everything</button>
      }
    </header>

    @if (memories.hasValue()) {
      <ul class="card memories">
        @for (memory of memories.value(); track memory.id) {
          <li>
            <span>{{ memory.content }}</span>
            <span class="meta">
              <time class="muted" [attr.datetime]="memory.createdAt">{{ memory.createdAt | date: 'medium' }}</time>
              <button type="button" class="link-button" [disabled]="busy()" (click)="deleteMemory(memory)"
                      [attr.aria-label]="'Forget: ' + memory.content">Forget</button>
            </span>
          </li>
        } @empty {
          <li class="muted">
            Nothing remembered yet. Tell the agent something about your setup in the <a routerLink="/chat">chat</a>,
            e.g. “Remember that I use a MacBook”.
          </li>
        }
      </ul>
    } @else if (memories.isLoading()) {
      <p class="muted">Loading…</p>
    }

    <section class="card privacy" aria-labelledby="privacy-title">
      <h2 id="privacy-title">Your data</h2>
      <p class="muted">
        DeskPilot keeps your conversations (questions, answers and the agent's reasoning), these memories and the tickets
        you requested. You can download all of it, or delete your conversations and memories. Tickets stay with the
        helpdesk.
      </p>
      <div class="actions">
        <button type="button" class="button" [disabled]="busy()" (click)="download()">Download my data</button>
        <button type="button" class="button danger" [disabled]="busy()" (click)="deleteAllData()">
          Delete my conversations and memories
        </button>
      </div>
    </section>
  `,
  styles: `
    :host { display: grid; gap: 1rem; }
    .memories { list-style: none; margin: 0; padding: 0.5rem 1rem; }
    li { display: flex; justify-content: space-between; align-items: baseline; gap: 1rem; padding: 0.6rem 0; border-bottom: 1px solid var(--border); }
    li:last-child { border-bottom: none; }
    .meta { display: flex; gap: 0.75rem; align-items: baseline; white-space: nowrap; }
    time { font-size: 0.8rem; }
    .privacy h2 { font-size: 1rem; margin: 0 0 0.5rem; }
    .privacy p { margin: 0 0 0.75rem; }
    .actions { display: flex; flex-wrap: wrap; gap: 0.5rem; }
  `,
})
export class MemoriesPage {
  private readonly api = inject(AgentApi);
  private readonly privacy = inject(PrivacyApi);
  private readonly chat = inject(ChatStore);
  private readonly notifications = inject(Notifications);

  protected readonly memories = httpResource<Memory[]>(() => this.api.url('/me/memories'));
  protected readonly busy = signal(false);

  protected async deleteMemory(memory: Memory): Promise<void> {
    await this.run(() => firstValueFrom(this.privacy.deleteMemory(memory.id)), 'Forgotten.');
  }

  protected async deleteAllMemories(): Promise<void> {
    if (confirm('Forget everything the agent remembers about you?')) {
      await this.run(() => firstValueFrom(this.privacy.deleteAllMemories()), 'All memories deleted.');
    }
  }

  protected async download(): Promise<void> {
    this.busy.set(true);
    try {
      saveBlob(await firstValueFrom(this.privacy.exportData()), 'deskpilot-my-data.json');
    } catch {
      // Toast shown by the error interceptor.
    } finally {
      this.busy.set(false);
    }
  }

  protected async deleteAllData(): Promise<void> {
    if (!confirm('Delete all your conversations and memories? This cannot be undone. Your tickets are kept.')) {
      return;
    }
    const deleted = await this.run(() => firstValueFrom(this.privacy.deleteAllData()),
      'Your conversations and memories were deleted.');
    if (deleted) {
      // The current conversation no longer exists: start a fresh one for the chat.
      await this.chat.newConversation();
    }
  }

  /** @returns false if the backend refused (e.g. 409 while a request is in progress; the toast explains why) */
  private async run(action: () => Promise<void>, done: string): Promise<boolean> {
    this.busy.set(true);
    try {
      await action();
      this.notifications.info(done);
      this.memories.reload();
      return true;
    } catch {
      return false;
    } finally {
      this.busy.set(false);
    }
  }
}
