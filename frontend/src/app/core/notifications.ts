import { Injectable, computed, signal } from '@angular/core';

export interface Toast {
  id: number;
  kind: 'error' | 'info';
  message: string;
}

const TOAST_LIFETIME_MS = 6000;

@Injectable({ providedIn: 'root' })
export class Notifications {
  private nextId = 1;
  private readonly items = signal<Toast[]>([]);

  readonly toasts = this.items.asReadonly();
  readonly hasToasts = computed(() => this.items().length > 0);

  error(message: string): void {
    this.push('error', message);
  }

  info(message: string): void {
    this.push('info', message);
  }

  dismiss(id: number): void {
    this.items.update((toasts) => toasts.filter((t) => t.id !== id));
  }

  private push(kind: Toast['kind'], message: string): void {
    const toast: Toast = { id: this.nextId++, kind, message };
    this.items.update((toasts) => [...toasts.slice(-3), toast]);
    setTimeout(() => this.dismiss(toast.id), TOAST_LIFETIME_MS);
  }
}

/** Counts in-flight HTTP requests for the global progress bar. */
@Injectable({ providedIn: 'root' })
export class LoadingIndicator {
  private readonly pending = signal(0);

  readonly active = computed(() => this.pending() > 0);

  start(): void {
    this.pending.update((n) => n + 1);
  }

  stop(): void {
    this.pending.update((n) => Math.max(0, n - 1));
  }
}
