import { httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { AgentApi } from '../core/agent-api';
import { Article } from '../core/models';

@Component({
  selector: 'app-knowledge-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="page-header">
      <div>
        <h1>Knowledge base</h1>
        <p class="muted">The articles the agent searches (vector similarity with nomic-embed-text). It may only answer from these.</p>
      </div>
    </header>

    <label class="search">
      <span class="visually-hidden">Filter articles</span>
      <input type="search" placeholder="Filter by title or keyword…" [value]="query()" (input)="query.set($any($event.target).value)" />
    </label>

    @if (articles.hasValue()) {
      <p class="muted count">{{ filtered().length }} of {{ articles.value().length }} articles</p>
      <div class="articles">
        @for (article of filtered(); track article.id) {
          <article class="card">
            <h2>{{ article.title }}</h2>
            <ul class="tags" aria-label="Keywords">
              @for (tag of article.tags.split(','); track tag) {
                <li>{{ tag }}</li>
              }
            </ul>
            <p>{{ article.content }}</p>
          </article>
        } @empty {
          <p class="muted">No article matches “{{ query() }}”.</p>
        }
      </div>
    }
  `,
  styles: `
    .search input { width: 100%; max-width: 28rem; }
    .count { margin: 0.75rem 0; }
    .articles { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 1rem; }
    h2 { font-size: 1rem; margin: 0 0 0.5rem; }
    .tags { list-style: none; padding: 0; margin: 0 0 0.75rem; display: flex; flex-wrap: wrap; gap: 0.3rem; }
    .tags li { font-size: 0.72rem; padding: 0.1rem 0.5rem; border-radius: 999px; background: var(--accent-bg); color: var(--accent); }
    p { margin: 0; font-size: 0.9rem; line-height: 1.5; }
  `,
})
export class KnowledgePage {
  private readonly api = inject(AgentApi);

  protected readonly articles = httpResource<Article[]>(() => this.api.url('/knowledge-base/articles'));
  protected readonly query = signal('');

  protected readonly filtered = computed(() => {
    const all = this.articles.hasValue() ? this.articles.value() : [];
    const q = this.query().trim().toLowerCase();
    return q ? all.filter((a) => `${a.title} ${a.tags} ${a.content}`.toLowerCase().includes(q)) : all;
  });
}
