import { DatePipe, DecimalPipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { firstValueFrom } from 'rxjs';
import { FormControl, FormGroup, FormRecord, NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';

import { AgentApi } from '../core/agent-api';
import { AuthService } from '../core/auth';
import { I18n, TranslationKey } from '../core/i18n';
import { AgentSettings, ToolInfo } from '../core/models';
import { ExperimentsApi, PromptVersion } from '../core/experiments';
import { ModelOptions } from '../core/model-options';
import { Notifications } from '../core/notifications';
import { SettingsStore } from '../core/settings-store';
import { isCloudProvider, modelChoice, parseModelChoice } from '../core/cloud';
import { ProvidersPanel } from './providers-panel';

type ToolControls = FormGroup<{ enabled: FormControl<boolean>; approval: FormControl<boolean> }>;

interface Preset {
  name: TranslationKey;
  hint: TranslationKey;
  values: Pick<AgentSettings, 'temperature' | 'topP' | 'contextWindow' | 'maxOutputTokens' | 'thinking' | 'maxIterations'>;
}

/** Quick starting points; every value can still be adjusted afterwards. */
const PRESETS: Preset[] = [
  { name: 'settings.presetFast', hint: 'settings.presetFastHint', values: { temperature: 0.2, topP: 0.9, contextWindow: 4096, maxOutputTokens: 512, thinking: false, maxIterations: 5 } },
  { name: 'settings.presetBalanced', hint: 'settings.presetBalancedHint', values: { temperature: 0.2, topP: 0.9, contextWindow: 8192, maxOutputTokens: 2048, thinking: false, maxIterations: 8 } },
  { name: 'settings.presetThorough', hint: 'settings.presetThoroughHint', values: { temperature: 0.3, topP: 0.95, contextWindow: 16384, maxOutputTokens: 4096, thinking: true, maxIterations: 12 } },
];

const GIB = 1024 ** 3;

@Component({
  selector: 'app-settings-page',
  imports: [ReactiveFormsModule, DatePipe, DecimalPipe, ProvidersPanel],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './settings-page.html',
  styleUrl: './settings-page.css',
})
export class SettingsPage {
  protected readonly i18n = inject(I18n);
  private readonly api = inject(AgentApi);
  private readonly fb = inject(NonNullableFormBuilder);
  protected readonly store = inject(SettingsStore);
  protected readonly auth = inject(AuthService);

  protected readonly presets = PRESETS;
  protected readonly gib = GIB;
  protected readonly options = inject(ModelOptions);
  protected readonly models = this.options.local;
  protected readonly tools = httpResource<ToolInfo[]>(() => this.api.url('/tools'), { defaultValue: [] });
  private readonly experimentsApi = inject(ExperimentsApi);
  private readonly notifications = inject(Notifications);
  /** Saved prompt versions (12m), admins only. */
  protected readonly prompts = httpResource<PromptVersion[]>(
    () => (this.auth.isAdmin() ? this.experimentsApi.promptsUrl : undefined),
    { defaultValue: [] },
  );
  protected readonly versionName = signal('');
  protected readonly savingVersion = signal(false);

  protected readonly form = this.fb.group({
    chatModel: ['', Validators.required],
    temperature: [0.2, [Validators.min(0), Validators.max(2)]],
    topP: [0.9, [Validators.min(0.05), Validators.max(1)]],
    contextWindow: [8192, [Validators.required, Validators.min(1024), Validators.max(131072)]],
    maxOutputTokens: [2048, [Validators.required, Validators.min(64), Validators.max(32768)]],
    thinking: [false],
    maxIterations: [8, [Validators.required, Validators.min(1), Validators.max(20)]],
    ragTopK: [3, [Validators.required, Validators.min(1), Validators.max(10)]],
    ragSimilarityThreshold: [0.55, [Validators.min(0), Validators.max(1)]],
    groundingCheck: [true],
    hybridSearch: [true],
    selfCheck: [true],
    specialists: [false],
    visionModel: [''],
    systemPrompt: ['', Validators.maxLength(8000)],
    tools: new FormRecord<ToolControls>({}),
  });

  private readonly value = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });
  private readonly loaded = signal(false);

  /** The chat select holds provider and model together, e.g. "mistral|mistral-small-latest" (12s). */
  protected readonly selectedChoice = computed(() => parseModelChoice(this.value().chatModel ?? ''));
  protected readonly selectedModel = computed(() =>
    this.selectedChoice().provider === 'ollama'
      ? (this.models.value().find((m) => m.name === this.selectedChoice().model) ?? null)
      : null,
  );
  protected readonly selectedIsCloud = computed(() => isCloudProvider(this.selectedChoice().provider));
  protected readonly selectedProviderLabel = computed(() => this.options.providerLabel(this.selectedChoice().provider));
  /** Shown when the saved model is not in the lists (e.g. a cloud model, whose list only admins can load). */
  protected readonly unlistedChat = computed(() => {
    const value = this.value().chatModel ?? '';
    return value && !this.options.find(value) ? value : null;
  });
  protected readonly unlistedVision = computed(() => {
    const value = this.value().visionModel ?? '';
    return value && !this.options.find(value) ? value : null;
  });
  protected readonly toolNames = computed(() => this.tools.value().map((t) => t.name));

  constructor() {
    void this.store.load();
    // Fill the form once both the settings and the tool list are known.
    effect(() => {
      const settings = this.store.settings();
      const tools = this.tools.value();
      if (settings && tools.length > 0 && !this.loaded()) {
        this.patch(settings, tools);
        this.loaded.set(true);
      }
    });
    // Everyone can read the settings; only admins can change them (the backend enforces it too).
    effect(() => {
      if (this.auth.isAdmin()) {
        this.form.enable({ emitEvent: false });
        this.lockExternalApprovals();
      } else {
        this.form.disable({ emitEvent: false });
      }
    });
  }

  /** Used by the unsavedChangesGuard. */
  hasUnsavedChanges(): boolean {
    return this.form.dirty;
  }

  protected toolControls(name: string): ToolControls {
    return this.form.controls.tools.controls[name];
  }

  protected applyPreset(preset: Preset): void {
    const thinking = preset.values.thinking && (this.selectedModel()?.supportsThinking ?? false);
    this.form.patchValue({ ...preset.values, thinking });
    this.form.markAsDirty();
  }

  protected async save(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const tools = Object.entries(v.tools);
    const saved = await this.store.save({
      chatModel: parseModelChoice(v.chatModel).model,
      chatProvider: parseModelChoice(v.chatModel).provider,
      temperature: v.temperature,
      topP: v.topP,
      contextWindow: v.contextWindow,
      maxOutputTokens: v.maxOutputTokens,
      thinking: v.thinking,
      maxIterations: v.maxIterations,
      ragTopK: v.ragTopK,
      ragSimilarityThreshold: v.ragSimilarityThreshold,
      groundingCheck: v.groundingCheck,
      hybridSearch: v.hybridSearch,
      selfCheck: v.selfCheck,
      specialists: v.specialists,
      visionModel: v.visionModel ? parseModelChoice(v.visionModel).model : null,
      visionProvider: v.visionModel ? parseModelChoice(v.visionModel).provider : null,
      disabledTools: tools.filter(([, t]) => !t.enabled).map(([name]) => name),
      approvalTools: tools.filter(([, t]) => t.approval).map(([name]) => name),
      systemPrompt: v.systemPrompt.trim() || null,
    });
    if (saved) {
      this.patch(saved, this.tools.value());
    }
  }

  protected async reset(): Promise<void> {
    if (!confirm(this.i18n.t('settings.confirmReset'))) {
      return;
    }
    const defaults = await this.store.reset();
    if (defaults) {
      this.patch(defaults, this.tools.value());
    }
  }

  /** Puts a saved prompt version into the prompt field (saved with the other settings). */
  protected loadPrompt(select: HTMLSelectElement): void {
    const prompt = this.prompts.value().find((p) => String(p.id) === select.value);
    if (prompt) {
      this.form.controls.systemPrompt.setValue(prompt.content);
      this.form.controls.systemPrompt.markAsDirty();
    }
    select.value = '';
  }

  protected async savePromptVersion(): Promise<void> {
    const name = this.versionName().trim();
    const content = this.form.controls.systemPrompt.value.trim();
    if (!name || !content) {
      return;
    }
    this.savingVersion.set(true);
    try {
      await firstValueFrom(this.experimentsApi.createPrompt(name, content));
      this.notifications.info(this.i18n.t('prompts.saved', { name }));
      this.versionName.set('');
      this.prompts.reload();
    } catch {
      // Toast shown by the error interceptor.
    } finally {
      this.savingVersion.set(false);
    }
  }

  protected discard(): void {
    const settings = this.store.settings();
    if (settings) {
      this.patch(settings, this.tools.value());
    }
  }

  private lockExternalApprovals(): void {
    for (const tool of this.tools.value().filter((t) => t.external)) {
      this.form.controls.tools.controls[tool.name]?.controls.approval.disable({ emitEvent: false });
    }
  }

  private patch(settings: AgentSettings, tools: ToolInfo[]): void {
    const record = this.form.controls.tools;
    for (const tool of tools) {
      // External (MCP) tools always need approval: the checkbox is shown checked and locked.
      const group = this.fb.group({
        enabled: !settings.disabledTools.includes(tool.name),
        approval: { value: tool.external || settings.approvalTools.includes(tool.name), disabled: tool.external },
      });
      if (record.contains(tool.name)) {
        record.setControl(tool.name, group);
      } else {
        record.addControl(tool.name, group);
      }
    }
    this.form.patchValue({
      ...settings,
      chatModel: modelChoice(settings.chatProvider, settings.chatModel),
      systemPrompt: settings.systemPrompt ?? '',
      visionModel: settings.visionModel ? modelChoice(settings.visionProvider, settings.visionModel) : '',
    });
    this.form.markAsPristine();
  }
}
