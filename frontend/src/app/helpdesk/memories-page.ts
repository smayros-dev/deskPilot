import { DatePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AgentApi } from '../core/agent-api';
import { ChatStore } from '../core/chat-store';
import { Memory } from '../core/models';

/** Long-term memory of the current user; reloads automatically when the user changes. */
@Component({
  selector: 'app-memories-page',
  imports: [DatePipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="page-header">
      <div>
        <h1>Memories of {{ store.userId() }}</h1>
        <p class="muted">Facts the agent saved with <code>save_memory</code> and recalls in later conversations.</p>
      </div>
    </header>

    @if (memories.hasValue()) {
      <ul class="card memories">
        @for (memory of memories.value(); track memory.id) {
          <li>
            <span>{{ memory.content }}</span>
            <time class="muted" [attr.datetime]="memory.createdAt">{{ memory.createdAt | date: 'medium' }}</time>
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
  `,
  styles: `
    .memories { list-style: none; margin: 0; padding: 0.5rem 1rem; }
    li { display: flex; justify-content: space-between; gap: 1rem; padding: 0.6rem 0; border-bottom: 1px solid var(--border); }
    li:last-child { border-bottom: none; }
    time { font-size: 0.8rem; white-space: nowrap; }
  `,
})
export class MemoriesPage {
  private readonly api = inject(AgentApi);
  protected readonly store = inject(ChatStore);

  // The URL is reactive: changing the user in the chat re-fetches this list.
  protected readonly memories = httpResource<Memory[]>(() =>
    this.api.url(`/users/${encodeURIComponent(this.store.userId())}/memories`),
  );
}
