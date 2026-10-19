import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { Notifications } from '../core/notifications';

@Component({
  selector: 'app-toast-container',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toasts" aria-live="polite" aria-atomic="false">
      @for (toast of notifications.toasts(); track toast.id) {
        <div class="toast" [class.error]="toast.kind === 'error'" role="status">
          <span>{{ toast.message }}</span>
          <button type="button" class="icon-button" aria-label="Dismiss" (click)="notifications.dismiss(toast.id)">✕</button>
        </div>
      }
    </div>
  `,
  styles: `
    .toasts {
      position: fixed;
      right: 1rem;
      bottom: 1rem;
      display: grid;
      gap: 0.5rem;
      z-index: 20;
      max-width: min(420px, calc(100vw - 2rem));
    }
    .toast {
      display: flex;
      gap: 0.75rem;
      align-items: start;
      justify-content: space-between;
      padding: 0.75rem 1rem;
      border-radius: var(--radius);
      background: var(--surface);
      border: 1px solid var(--border);
      border-left: 4px solid var(--info);
      box-shadow: var(--shadow);
    }
    .toast.error { border-left-color: var(--danger); }
  `,
})
export class ToastContainer {
  protected readonly notifications = inject(Notifications);
}
