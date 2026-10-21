import { HttpContext, HttpErrorResponse, httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject } from '@angular/core';

import { API_CONFIG, SILENT_ERRORS } from '../core/api-config';
import { I18n, TranslationKey } from '../core/i18n';
import { Health } from '../core/models';

const REFRESH_MS = 30_000;
/** A backend that cannot reach its database answers health checks only after its pool times out (30 s). */
const TIMEOUT_MS = 10_000;

type State = 'unknown' | 'up' | 'model' | 'database' | 'backend';

/**
 * Shows whether the agent can work, polling the actuator health endpoint: model (Ollama) online, or which part is
 * missing — the model, the database, or the backend itself — so the hint points at the right thing to restart.
 */
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
    [data-state='model'] .dot,
    [data-state='database'] .dot,
    [data-state='backend'] .dot { background: var(--danger); }
  `,
})
export class HealthPill {
  private readonly config = inject(API_CONFIG);
  private readonly i18n = inject(I18n);

  private readonly health = httpResource<Health>(() => ({
    url: `${this.config.actuatorUrl}/health`,
    timeout: TIMEOUT_MS,
    context: new HttpContext().set(SILENT_ERRORS, true),
  }));

  /** The health report: also sent with a 503 when a component is DOWN, which httpResource reports as an error. */
  private readonly report = computed<Health | null>(() => {
    if (this.health.hasValue()) {
      return this.health.value();
    }
    const error = this.health.error();
    const body = error instanceof HttpErrorResponse ? (error.error as Health | null) : null;
    return body?.components ? body : null;
  });

  protected readonly state = computed<State>(() => {
    if (this.health.isLoading() && !this.health.hasValue() && !this.health.error()) {
      return 'unknown';
    }
    const components = this.report()?.components;
    if (!components) {
      return 'backend';
    }
    if (components['db']?.status !== undefined && components['db'].status !== 'UP') {
      return 'database';
    }
    return components['ollama']?.status === 'UP' ? 'up' : 'model';
  });

  protected readonly label = computed(() => this.i18n.t(LABELS[this.state()]));

  protected readonly tooltip = computed(() => this.i18n.t(HINTS[this.state()]));

  constructor() {
    const timer = setInterval(() => this.health.reload(), REFRESH_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }
}

const LABELS: Record<State, TranslationKey> = {
  unknown: 'health.checking',
  up: 'health.online',
  model: 'health.offline',
  database: 'health.dbDown',
  backend: 'health.backendDown',
};

const HINTS: Record<State, TranslationKey> = {
  unknown: 'health.checking',
  up: 'health.onlineHint',
  model: 'health.offlineHint',
  database: 'health.dbDownHint',
  backend: 'health.backendDownHint',
};
