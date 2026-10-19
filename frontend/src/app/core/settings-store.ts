import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { AgentApi } from './agent-api';
import { AgentSettings } from './models';
import { Notifications } from './notifications';

/** Agent settings shared by the settings page and the chat's model switcher. */
@Injectable({ providedIn: 'root' })
export class SettingsStore {
  private readonly api = inject(AgentApi);
  private readonly notifications = inject(Notifications);

  private readonly _settings = signal<AgentSettings | null>(null);
  private readonly _saving = signal(false);

  readonly settings = this._settings.asReadonly();
  readonly saving = this._saving.asReadonly();
  readonly chatModel = computed(() => this._settings()?.chatModel ?? null);

  async load(): Promise<AgentSettings | null> {
    try {
      this._settings.set(await firstValueFrom(this.api.getSettings()));
    } catch {
      // Toast shown by the error interceptor.
    }
    return this._settings();
  }

  /** @returns the saved settings, or null if the backend rejected them (the toast explains why) */
  async save(settings: AgentSettings): Promise<AgentSettings | null> {
    this._saving.set(true);
    try {
      const saved = await firstValueFrom(this.api.updateSettings(settings));
      this._settings.set(saved);
      this.notifications.info('Settings saved. They apply from the next message.');
      return saved;
    } catch {
      return null;
    } finally {
      this._saving.set(false);
    }
  }

  async switchModel(model: string): Promise<void> {
    const current = this._settings() ?? (await this.load());
    if (current && current.chatModel !== model) {
      await this.save({ ...current, chatModel: model });
    }
  }

  async reset(): Promise<AgentSettings | null> {
    this._saving.set(true);
    try {
      const defaults = await firstValueFrom(this.api.resetSettings());
      this._settings.set(defaults);
      this.notifications.info('Settings reset to the application defaults.');
      return defaults;
    } catch {
      return null;
    } finally {
      this._saving.set(false);
    }
  }
}
