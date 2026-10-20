import { httpResource } from '@angular/common/http';
import { Injectable, computed, inject, resource } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { AgentApi } from './agent-api';
import { AuthService } from './auth';
import { CloudModel, LOCAL_PROVIDER, ProviderStatus, ProvidersApi, modelChoice } from './cloud';
import { I18n } from './i18n';
import { ModelInfo } from './models';

export interface ModelOption {
  /** Select value: provider and model, see {@link modelChoice}. */
  value: string;
  provider: string;
  model: string;
  label: string;
  supportsTools: boolean;
  supportsVision: boolean;
  supportsThinking: boolean;
}

export interface ModelGroup {
  provider: string;
  label: string;
  cloud: boolean;
  options: ModelOption[];
}

/**
 * Models the agent can use, grouped by provider: the local Ollama models, then each enabled cloud provider with a key
 * (12s). Cloud models are only listed for admins, who are the only ones allowed to change the model.
 */
@Injectable({ providedIn: 'root' })
export class ModelOptions {
  private readonly api = inject(AgentApi);
  private readonly providersApi = inject(ProvidersApi);
  private readonly auth = inject(AuthService);
  private readonly i18n = inject(I18n);

  readonly local = httpResource<ModelInfo[]>(() => this.api.url('/models'), { defaultValue: [] });
  readonly providers = httpResource<ProviderStatus[]>(() => (this.auth.isAdmin() ? this.providersApi.listUrl : undefined), {
    defaultValue: [],
  });
  private readonly cloudModels = resource({
    params: () => this.providers.value().filter((p) => p.available).map((p) => p.id),
    loader: async ({ params }) => {
      const lists = await Promise.all(
        params.map(async (id) => [id, await firstValueFrom(this.providersApi.models(id)).catch(() => [] as CloudModel[])] as const),
      );
      return Object.fromEntries(lists) as Record<string, CloudModel[]>;
    },
    defaultValue: {} as Record<string, CloudModel[]>,
  });

  readonly groups = computed<ModelGroup[]>(() => {
    const local: ModelGroup = {
      provider: LOCAL_PROVIDER,
      label: this.i18n.t('models.localGroup'),
      cloud: false,
      options: this.local
        .value()
        .filter((m) => m.chatModel)
        .map((m) => ({
          value: modelChoice(LOCAL_PROVIDER, m.name),
          provider: LOCAL_PROVIDER,
          model: m.name,
          label: m.parameterSize ? `${m.name} · ${m.parameterSize}` : m.name,
          supportsTools: m.supportsTools,
          supportsVision: m.supportsVision,
          supportsThinking: m.supportsThinking,
        })),
    };
    const cloud = this.providers
      .value()
      .filter((p) => p.available)
      .map((p) => ({
        provider: p.id,
        label: p.label,
        cloud: true,
        options: (this.cloudModels.value()[p.id] ?? []).map((m) => ({
          value: modelChoice(p.id, m.id),
          provider: p.id,
          model: m.id,
          label: m.id,
          supportsTools: m.supportsTools,
          supportsVision: m.supportsVision,
          supportsThinking: false,
        })),
      }));
    return [local, ...cloud];
  });

  readonly loading = computed(() => this.local.isLoading() || this.providers.isLoading() || this.cloudModels.isLoading());

  find(value: string): ModelOption | null {
    for (const group of this.groups()) {
      const option = group.options.find((o) => o.value === value);
      if (option) {
        return option;
      }
    }
    return null;
  }

  providerLabel(provider: string): string {
    return provider === LOCAL_PROVIDER
      ? this.i18n.t('models.localGroup')
      : (this.providers.value().find((p) => p.id === provider)?.label ?? provider);
  }

  reload(): void {
    this.local.reload();
    this.providers.reload();
  }
}
