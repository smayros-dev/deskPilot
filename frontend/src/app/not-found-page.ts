import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { I18n } from './core/i18n';

@Component({
  selector: 'app-not-found-page',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card empty">
      <h1>{{ i18n.t('notFound.title') }}</h1>
      <p>{{ i18n.t('notFound.text') }}</p>
      <a class="button" routerLink="/chat">{{ i18n.t('notFound.back') }}</a>
    </section>
  `,
  styles: `
    .empty { text-align: center; padding: 3rem 1.5rem; }
  `,
})
export class NotFoundPage {
  protected readonly i18n = inject(I18n);
}
