import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Citation, citationsOf } from '../core/citations';
import { I18n } from '../core/i18n';
import { Run } from '../core/models';
import { parseMarkdown } from './markdown';

/**
 * An agent answer: Markdown rendered through the template (never as HTML), [n] markers shown as numbered chips,
 * and the cited knowledge-base articles listed under "Sources".
 */
@Component({
  selector: 'app-answer',
  imports: [NgTemplateOutlet, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="markdown">
      @for (block of blocks(); track $index) {
        @switch (block.kind) {
          @case ('heading') {
            <p class="heading"><ng-container [ngTemplateOutlet]="inline" [ngTemplateOutletContext]="{ $implicit: block.inlines }" /></p>
          }
          @case ('paragraph') {
            <p>
              @for (line of block.lines; track $index; let last = $last) {
                <ng-container [ngTemplateOutlet]="inline" [ngTemplateOutletContext]="{ $implicit: line }" />
                @if (!last) { <br /> }
              }
            </p>
          }
          @case ('list') {
            @if (block.ordered) {
              <ol [attr.start]="block.start">
                @for (item of block.items; track $index) {
                  <li><ng-container [ngTemplateOutlet]="inline" [ngTemplateOutletContext]="{ $implicit: item }" /></li>
                }
              </ol>
            } @else {
              <ul>
                @for (item of block.items; track $index) {
                  <li><ng-container [ngTemplateOutlet]="inline" [ngTemplateOutletContext]="{ $implicit: item }" /></li>
                }
              </ul>
            }
          }
        }
      }
    </div>

    @if (citations().length > 0) {
      <div class="sources">
        <span class="label">{{ i18n.t('answer.sources') }}</span>
        <ol>
          @for (citation of citations(); track citation.articleId) {
            <li>
              <span class="cite">{{ citation.number }}</span>
              <a routerLink="/helpdesk/knowledge" [queryParams]="{ article: citation.articleId }">{{ citation.title }}</a>
            </li>
          }
        </ol>
      </div>
    }

    <ng-template #inline let-inlines>
      @for (part of inlines; track $index) {
        @switch (part.kind) {
          @case ('strong') { <strong>{{ part.text }}</strong> }
          @case ('em') { <em>{{ part.text }}</em> }
          @case ('code') { <code>{{ part.text }}</code> }
          @case ('link') { <a [href]="part.href" target="_blank" rel="noopener noreferrer">{{ part.text }}</a> }
          @case ('cite') {
            @let citation = citationById().get(part.id);
            @if (citation) {
              <sup><a class="cite" routerLink="/helpdesk/knowledge" [queryParams]="{ article: citation.articleId }"
                      [title]="citation.title" [attr.aria-label]="i18n.t('answer.source', { n: citation.number, title: citation.title })">{{ citation.number }}</a></sup>
            } @else {
              <span>[{{ part.id }}]</span>
            }
          }
          @default { <span>{{ part.text }}</span> }
        }
      }
    </ng-template>
  `,
  styles: `
    :host { display: block; }
    .markdown p { margin: 0 0 0.5rem; white-space: normal; overflow-wrap: anywhere; }
    .markdown p:last-child { margin-bottom: 0; }
    .markdown .heading { font-weight: 600; }
    .markdown ol, .markdown ul { margin: 0 0 0.5rem; padding-left: 1.4rem; }
    .markdown li { margin: 0.15rem 0; }
    .markdown code { font-size: 0.85em; padding: 0.05rem 0.3rem; border-radius: 4px; background: var(--surface-muted); }
    .cite {
      display: inline-flex; align-items: center; justify-content: center;
      min-width: 1.1rem; height: 1.1rem; padding: 0 0.25rem; margin-left: 0.1rem;
      border-radius: 999px; font-size: 0.68rem; font-weight: 600; line-height: 1;
      background: var(--accent-bg); color: var(--accent); text-decoration: none;
    }
    sup { line-height: 0; }
    .sources { margin-top: 0.6rem; padding-top: 0.5rem; border-top: 1px solid var(--border); font-size: 0.82rem; }
    .sources .label { color: var(--text-muted); font-weight: 600; }
    .sources ol { list-style: none; margin: 0.3rem 0 0; padding: 0; display: grid; gap: 0.2rem; }
    .sources li { display: flex; align-items: center; gap: 0.4rem; }
  `,
})
export class Answer {
  protected readonly i18n = inject(I18n);
  readonly text = input.required<string | null | undefined>();
  /** The run the answer belongs to; its knowledge-base searches tell which article each [n] marker means. */
  readonly run = input<Run | null>(null);

  protected readonly blocks = computed(() => parseMarkdown(this.text()));
  protected readonly citations = computed<Citation[]>(() => citationsOf(this.text(), this.run()));
  protected readonly citationById = computed(() => new Map(this.citations().map((c) => [c.articleId, c])));
}
