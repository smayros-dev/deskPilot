import { HttpContext, httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject } from '@angular/core';

import { API_CONFIG, SILENT_ERRORS } from '../core/api-config';
import { Health } from '../core/models';

const REFRESH_MS = 30_000;

/** Shows whether the backend and the local model (Ollama) are up; polls the actuator health endpoint. */
@Component({
  selector: 'app-health-pill',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="pill" [attr.data-state]="state()" [title]="tooltip()">
      <span class="dot" aria-hidden="true"></span>{{ label() }}
    </span>
  `,
  styles: `
    .pill { display: inline-flex; align-items: center; gap: 0.4rem; font-size: 0.8rem; color: var(--text-muted); }
    .dot { width: 0.55rem; height: 0.55rem; border-radius: 50%; background: var(--text-muted); }
    [data-state='up'] .dot { background: var(--ok); }
    [data-state='down'] .dot { background: var(--danger); }
  `,
})
export class HealthPill {
  private readonly config = inject(API_CONFIG);

  private readonly health = httpResource<Health>(() => ({
    url: `${this.config.actuatorUrl}/health`,
    context: new HttpContext().set(SILENT_ERRORS, true),
  }));

  protected readonly state = computed(() => {
    // Actuator answers 503 with a body when something is DOWN, which httpResource reports as an error.
    if (this.health.isLoading() && !this.health.hasValue()) {
      return 'unknown';
    }
    return this.ollamaStatus() === 'UP' ? 'up' : 'down';
  });

  protected readonly ollamaStatus = computed(() =>
    this.health.hasValue() ? (this.health.value().components?.['ollama']?.status ?? 'UNKNOWN') : 'DOWN',
  );

  protected readonly label = computed(() => {
    switch (this.state()) {
      case 'up':
        return 'Model online';
      case 'down':
        return 'Model offline';
      default:
        return 'Checking…';
    }
  });

  protected readonly tooltip = computed(() =>
    this.state() === 'down' ? 'Start Ollama (ollama serve) and the backend, then wait for the next check.' : 'Ollama is reachable',
  );

  constructor() {
    const timer = setInterval(() => this.health.reload(), REFRESH_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }
}
