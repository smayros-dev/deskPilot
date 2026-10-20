import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { modelChoice, parseModelChoice } from '../core/cloud';
import { I18n } from '../core/i18n';
import { ModelOptions } from '../core/model-options';
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
        [value]="current()"
        [disabled]="disabled() || store.saving() || options.groups()[0].options.length === 0"
        (change)="switch($any($event.target).value)"
      >
        @for (group of options.groups(); track group.provider) {
          <optgroup [label]="group.cloud ? '☁ ' + group.label : group.label">
            @for (option of group.options; track option.value) {
              @if (option.supportsTools) {
                <option [value]="option.value" [selected]="option.value === current()">{{ option.model }}</option>
              }
            }
          </optgroup>
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
  protected readonly options = inject(ModelOptions);
  protected readonly current = computed(() => {
    const settings = this.store.settings();
    return settings ? modelChoice(settings.chatProvider, settings.chatModel) : '';
  });

  constructor() {
    if (!this.store.settings()) {
      void this.store.load();
    }
  }

  protected switch(value: string): void {
    const { provider, model } = parseModelChoice(value);
    void this.store.switchModel(model, provider);
  }
}
