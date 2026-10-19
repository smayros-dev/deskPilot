import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found-page',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card empty">
      <h1>Page not found</h1>
      <p>The page or run you are looking for does not exist (runs are kept, but sessions reset when the backend restarts).</p>
      <a class="button" routerLink="/chat">Back to the chat</a>
    </section>
  `,
  styles: `
    .empty { text-align: center; padding: 3rem 1.5rem; }
  `,
})
export class NotFoundPage {}
