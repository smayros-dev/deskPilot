import { DatePipe, DecimalPipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, FormRecord, NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';

import { AgentApi } from '../core/agent-api';
import { AuthService } from '../core/auth';
import { AgentSettings, ModelInfo, ToolInfo } from '../core/models';
import { SettingsStore } from '../core/settings-store';

type ToolControls = FormGroup<{ enabled: FormControl<boolean>; approval: FormControl<boolean> }>;

interface Preset {
  name: string;
  hint: string;
  values: Pick<AgentSettings, 'temperature' | 'topP' | 'contextWindow' | 'maxOutputTokens' | 'thinking' | 'maxIterations'>;
}

/** Quick starting points; every value can still be adjusted afterwards. */
const PRESETS: Preset[] = [
  { name: 'Fast', hint: 'Short answers, small context. Best on CPU.', values: { temperature: 0.2, topP: 0.9, contextWindow: 4096, maxOutputTokens: 512, thinking: false, maxIterations: 5 } },
  { name: 'Balanced', hint: 'The default trade-off.', values: { temperature: 0.2, topP: 0.9, contextWindow: 8192, maxOutputTokens: 2048, thinking: false, maxIterations: 8 } },
  { name: 'Thorough', hint: 'Reasoning on, larger context. Needs a GPU to be usable.', values: { temperature: 0.3, topP: 0.95, contextWindow: 16384, maxOutputTokens: 4096, thinking: true, maxIterations: 12 } },
];

const GIB = 1024 ** 3;

@Component({
  selector: 'app-settings-page',
  imports: [ReactiveFormsModule, DatePipe, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './settings-page.html',
  styleUrl: './settings-page.css',
})
export class SettingsPage {
  private readonly api = inject(AgentApi);
  private readonly fb = inject(NonNullableFormBuilder);
  protected readonly store = inject(SettingsStore);
  protected readonly auth = inject(AuthService);

  protected readonly presets = PRESETS;
  protected readonly gib = GIB;
  protected readonly models = httpResource<ModelInfo[]>(() => this.api.url('/models'), { defaultValue: [] });
  protected readonly tools = httpResource<ToolInfo[]>(() => this.api.url('/tools'), { defaultValue: [] });

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
    systemPrompt: ['', Validators.maxLength(8000)],
    tools: new FormRecord<ToolControls>({}),
  });

  private readonly value = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });
  private readonly loaded = signal(false);

  protected readonly chatModels = computed(() => this.models.value().filter((m) => m.chatModel));
  protected readonly selectedModel = computed(() => this.chatModels().find((m) => m.name === this.value().chatModel) ?? null);
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
      chatModel: v.chatModel,
      temperature: v.temperature,
      topP: v.topP,
      contextWindow: v.contextWindow,
      maxOutputTokens: v.maxOutputTokens,
      thinking: v.thinking,
      maxIterations: v.maxIterations,
      ragTopK: v.ragTopK,
      ragSimilarityThreshold: v.ragSimilarityThreshold,
      disabledTools: tools.filter(([, t]) => !t.enabled).map(([name]) => name),
      approvalTools: tools.filter(([, t]) => t.approval).map(([name]) => name),
      systemPrompt: v.systemPrompt.trim() || null,
    });
    if (saved) {
      this.patch(saved, this.tools.value());
    }
  }

  protected async reset(): Promise<void> {
    if (!confirm('Reset all settings to the application defaults?')) {
      return;
    }
    const defaults = await this.store.reset();
    if (defaults) {
      this.patch(defaults, this.tools.value());
    }
  }

  protected discard(): void {
    const settings = this.store.settings();
    if (settings) {
      this.patch(settings, this.tools.value());
    }
  }

  private patch(settings: AgentSettings, tools: ToolInfo[]): void {
    const record = this.form.controls.tools;
    for (const tool of tools) {
      const group = this.fb.group({
        enabled: !settings.disabledTools.includes(tool.name),
        approval: settings.approvalTools.includes(tool.name),
      });
      if (record.contains(tool.name)) {
        record.setControl(tool.name, group);
      } else {
        record.addControl(tool.name, group);
      }
    }
    this.form.patchValue({ ...settings, systemPrompt: settings.systemPrompt ?? '' });
    this.form.markAsPristine();
  }
}
