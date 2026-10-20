import { httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, ElementRef, afterRenderEffect, computed, inject, input, signal } from '@angular/core';

import { AgentApi } from '../core/agent-api';
import { AuthService } from '../core/auth';
import { I18n } from '../core/i18n';
import { Article } from '../core/models';
import { DocumentsPanel } from './documents-panel';

@Component({
  selector: 'app-knowledge-page',
  imports: [DocumentsPanel],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="page-header">
      <div>
        <h1>{{ i18n.t('knowledge.title') }}</h1>
        <p class="muted">{{ i18n.t('knowledge.subtitle') }}</p>
      </div>
    </header>

    @if (auth.isAdmin()) {
      <app-documents-panel (changed)="articles.reload()" />
    }

    <label class="search">
      <span class="visually-hidden">{{ i18n.t('knowledge.filter') }}</span>
      <input type="search" [placeholder]="i18n.t('knowledge.filterPlaceholder')" [value]="query()" (input)="query.set($any($event.target).value)" />
    </label>

    @if (articles.hasValue()) {
      <p class="muted count">{{ i18n.t('knowledge.count', { shown: filtered().length, total: articles.value().length }) }}</p>
      <div class="articles">
        @for (article of filtered(); track article.id) {
          <article class="card" [id]="'article-' + article.id" [class.highlight]="article.id === highlighted()">
            <h2>{{ article.title }}</h2>
            @if (article.documentTitle) {
              <p class="source">📄 {{ i18n.t('knowledge.fromDocument') }} <strong>{{ article.documentTitle }}</strong></p>
            } @else {
              <ul class="tags" [attr.aria-label]="i18n.t('knowledge.keywords')">
                @for (tag of article.tags.split(','); track tag) {
                  <li>{{ tag }}</li>
                }
              </ul>
            }
            <p>{{ article.content }}</p>
          </article>
        } @empty {
          <p class="muted">{{ i18n.t('knowledge.noMatch', { query: query() }) }}</p>
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
    p { margin: 0; font-size: 0.9rem; line-height: 1.5; white-space: pre-line; }
    .source { font-size: 0.8rem; color: var(--text-muted); margin-bottom: 0.6rem; }
    app-documents-panel { display: block; margin: 1rem 0; }
    .highlight { outline: 2px solid var(--accent); outline-offset: 2px; }
  `,
})
export class KnowledgePage {
  protected readonly i18n = inject(I18n);
  private readonly api = inject(AgentApi);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly auth = inject(AuthService);

  /** `?article=4`, e.g. from a cited source in an answer: that article is highlighted and scrolled into view. */
  readonly article = input<string | undefined>();
  protected readonly highlighted = computed(() => Number(this.article()) || null);

  protected readonly articles = httpResource<Article[]>(() => this.api.url('/knowledge-base/articles'));
  protected readonly query = signal('');

  protected readonly filtered = computed(() => {
    const all = this.articles.hasValue() ? this.articles.value() : [];
    const q = this.query().trim().toLowerCase();
    return q ? all.filter((a) => `${a.title} ${a.tags} ${a.content} ${a.documentTitle ?? ''}`.toLowerCase().includes(q)) : all;
  });

  constructor() {
    let scrolledTo: number | null = null;
    afterRenderEffect(() => {
      const id = this.highlighted();
      // Scroll once per highlighted article, not again on every filter keystroke.
      if (id !== null && id !== scrolledTo && this.filtered().length > 0) {
        const target = this.host.nativeElement.querySelector(`#article-${id}`);
        if (target) {
          target.scrollIntoView({ block: 'center' });
          scrolledTo = id;
        }
      }
    });
  }
}
