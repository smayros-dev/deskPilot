import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ProviderStatus, ProviderTestResult, ProvidersApi } from '../core/cloud';
import { I18n } from '../core/i18n';
import { ModelOptions } from '../core/model-options';
import { Notifications } from '../core/notifications';

/**
 * Cloud providers (admins, 12s): enable a provider, store or remove its API key (written only, never shown back) and
 * test the connection. Keys from environment variables take precedence and cannot be edited here.
 */
@Component({
  selector: 'app-providers-panel',
  imports: [DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 id="providers-title">{{ i18n.t('providers.title') }}</h2>
    <p class="hint">{{ i18n.t('providers.intro') }}</p>
    @for (provider of options.providers.value(); track provider.id) {
      <div class="provider" [class.available]="provider.available">
        <div class="head">
          <strong>{{ provider.label }}</strong>
          <label class="toggle">
            <input type="checkbox" [checked]="provider.enabled" [disabled]="busy() === provider.id"
                   (change)="setEnabled(provider, $any($event.target).checked)" />
            {{ i18n.t('providers.enabled') }}
          </label>
          <span class="status" [attr.data-state]="provider.available ? 'ok' : 'off'">
            {{ provider.available ? i18n.t('providers.ready') : i18n.t('providers.notReady') }}
          </span>
        </div>

        <p class="key">
          @switch (provider.keySource) {
            @case ('ENVIRONMENT') { 🔒 {{ i18n.t('providers.keyFromEnv', { hint: provider.keyHint ?? '' }) }} }
            @case ('STORED') { 🔑 {{ i18n.t('providers.keyStored', { hint: provider.keyHint ?? '' }) }} }
            @default { {{ i18n.t('providers.noKey') }} }
          }
          @if (provider.updatedAt) {
            <span class="muted"> · {{ i18n.t('providers.updated', { by: provider.updatedBy ?? '' }) }} {{ provider.updatedAt | date: 'short' : undefined : i18n.locale() }}</span>
          }
        </p>

        @if (provider.keySource !== 'ENVIRONMENT') {
          <div class="key-form">
            <label class="visually-hidden" [for]="'key-' + provider.id">{{ i18n.t('providers.apiKey', { provider: provider.label }) }}</label>
            <input [id]="'key-' + provider.id" type="password" autocomplete="off" spellcheck="false" maxlength="500"
                   [placeholder]="i18n.t('providers.keyPlaceholder')" #key />
            <button type="button" class="button" [disabled]="busy() === provider.id" (click)="saveKey(provider, key)">
              {{ i18n.t('providers.saveKey') }}
            </button>
            @if (provider.keySource === 'STORED') {
              <button type="button" class="button danger" [disabled]="busy() === provider.id" (click)="removeKey(provider)">
                {{ i18n.t('providers.removeKey') }}
              </button>
            }
            <a [href]="provider.keysUrl" target="_blank" rel="noopener">{{ i18n.t('providers.getKey') }}</a>
          </div>
        }

        <div class="test">
          <button type="button" class="button" [disabled]="!provider.available || busy() === provider.id" (click)="test(provider)">
            {{ i18n.t('providers.test') }}
          </button>
          @if (results()[provider.id]; as result) {
            <span [class.ok]="result.ok" [class.error]="!result.ok" role="status">
              {{ result.ok
                ? i18n.t('providers.testOk', { ms: result.latencyMillis, models: result.models })
                : i18n.t('providers.testFailed', { error: result.error ?? '' }) }}
            </span>
          }
        </div>
      </div>
    }
    <p class="notice cloud">☁ {{ i18n.t('providers.privacy') }}</p>
  `,
  styles: `
    h2 { font-size: 1rem; margin: 0 0 0.25rem; }
    .provider { border: 1px solid var(--border); border-radius: var(--radius); padding: 0.75rem; margin-block: 0.75rem; display: grid; gap: 0.5rem; }
    .provider.available { border-inline-start: 3px solid var(--ok); }
    .head { display: flex; flex-wrap: wrap; align-items: center; gap: 1rem; }
    .status { margin-inline-start: auto; font-size: 0.8rem; }
    .status[data-state='ok'] { color: var(--ok); }
    .status[data-state='off'] { color: var(--text-muted); }
    .key { margin: 0; font-size: 0.88rem; }
    .key-form, .test { display: flex; flex-wrap: wrap; align-items: center; gap: 0.5rem; }
    .key-form input { flex: 1; min-width: 14rem; }
    .ok { color: var(--ok); font-size: 0.85rem; }
    .error { color: var(--danger); font-size: 0.85rem; }
    .notice.cloud { margin: 0.5rem 0 0; font-size: 0.85rem; color: var(--text-muted); }
  `,
})
export class ProvidersPanel {
  private readonly api = inject(ProvidersApi);
  private readonly notifications = inject(Notifications);
  protected readonly options = inject(ModelOptions);
  protected readonly i18n = inject(I18n);

  /** A provider changed (enabled, key): the model lists must be refreshed. */
  readonly changed = output<void>();

  protected readonly busy = signal<string | null>(null);
  protected readonly results = signal<Record<string, ProviderTestResult>>({});

  protected setEnabled(provider: ProviderStatus, enabled: boolean): Promise<void> {
    return this.update(provider, { enabled });
  }

  protected async saveKey(provider: ProviderStatus, input: HTMLInputElement): Promise<void> {
    const apiKey = input.value.trim();
    if (!apiKey) {
      return;
    }
    // The first key also enables the provider, which is what an admin adding a key expects.
    await this.update(provider, provider.enabled ? { apiKey } : { apiKey, enabled: true });
    input.value = '';
  }

  protected async removeKey(provider: ProviderStatus): Promise<void> {
    if (confirm(this.i18n.t('providers.confirmRemove', { provider: provider.label }))) {
      await this.update(provider, { removeKey: true });
    }
  }

  protected async test(provider: ProviderStatus): Promise<void> {
    this.busy.set(provider.id);
    try {
      const result = await firstValueFrom(this.api.test(provider.id));
      this.results.update((results) => ({ ...results, [provider.id]: result }));
    } catch {
      // Toast shown by the error interceptor.
    } finally {
      this.busy.set(null);
    }
  }

  private async update(provider: ProviderStatus, change: { enabled?: boolean; apiKey?: string; removeKey?: boolean }): Promise<void> {
    this.busy.set(provider.id);
    try {
      await firstValueFrom(this.api.update(provider.id, change));
      this.notifications.info(this.i18n.t('providers.saved', { provider: provider.label }));
      this.results.update(({ [provider.id]: _removed, ...rest }) => rest);
      this.options.reload();
      this.changed.emit();
    } catch {
      // Toast shown by the error interceptor.
    } finally {
      this.busy.set(null);
    }
  }
}
