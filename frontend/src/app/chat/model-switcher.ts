import { httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AgentApi } from '../core/agent-api';
import { I18n } from '../core/i18n';
import { ModelInfo } from '../core/models';
import { SettingsStore } from '../core/settings-store';

/** Quick model switch from the chat; the full configuration lives on the settings page. */
@Component({
  selector: 'app-model-switcher',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <label class="field">
      <span>{{ i18n.t('modelSwitcher.model') }}</span>
      <select
        [value]="store.chatModel() ?? ''"
        [disabled]="disabled() || store.saving() || toolModels().length === 0"
        (change)="switch($any($event.target).value)"
      >
        @for (model of toolModels(); track model.name) {
          <option [value]="model.name" [selected]="model.name === store.chatModel()">{{ model.name }}</option>
        }
      </select>
    </label>
    <a routerLink="/settings" class="settings-link" [attr.aria-label]="i18n.t('modelSwitcher.allSettings')" [title]="i18n.t('modelSwitcher.allSettings')">⚙</a>
  `,
  styles: `
    :host { display: inline-flex; align-items: center; gap: 0.4rem; }
    .field { display: inline-flex; align-items: center; gap: 0.5rem; font-size: 0.85rem; color: var(--text-muted); }
    select { font: inherit; color: var(--text); background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); padding: 0.3rem 0.5rem; max-width: 12rem; }
    .settings-link { text-decoration: none; font-size: 1.1rem; color: var(--text-muted); }
  `,
})
export class ModelSwitcher {
  readonly disabled = input(false);

  protected readonly store = inject(SettingsStore);
  protected readonly i18n = inject(I18n);
  private readonly api = inject(AgentApi);
  private readonly models = httpResource<ModelInfo[]>(() => this.api.url('/models'), { defaultValue: [] });

  protected readonly toolModels = computed(() => this.models.value().filter((m) => m.chatModel && m.supportsTools));

  constructor() {
    if (!this.store.settings()) {
      void this.store.load();
    }
  }

  protected switch(model: string): void {
    void this.store.switchModel(model);
  }
}
