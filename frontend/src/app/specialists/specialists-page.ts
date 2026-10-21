import { DatePipe, DecimalPipe, PercentPipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import {
  FormArray,
  FormControl,
  FormGroup,
  FormRecord,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { firstValueFrom } from 'rxjs';

import { AgentApi } from '../core/agent-api';
import { I18n, TranslationKey } from '../core/i18n';
import { ToolInfo } from '../core/models';
import { Notifications } from '../core/notifications';
import { RoutePreview, SpecialistInfo, SpecialistRequest, SpecialistsApi } from '../core/specialists';

/** Keywords as typed: separated by commas or new lines. */
export function parseKeywords(text: string): string[] {
  const seen = new Set<string>();
  return text
    .split(/[,\n]/)
    .map((k) => k.trim())
    .filter((k) => k && !seen.has(k.toLowerCase()) && seen.add(k.toLowerCase()));
}

/**
 * Specialist agents (13g, admins): the keywords, instructions, examples and tools of each specialist the supervisor
 * routes messages to, and a box to try where a message would go. Changes apply to the next message.
 */
@Component({
  selector: 'app-specialists-page',
  imports: [ReactiveFormsModule, DatePipe, DecimalPipe, PercentPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './specialists-page.html',
  styleUrl: './specialists-page.css',
})
export class SpecialistsPage {
  protected readonly i18n = inject(I18n);
  private readonly api = inject(SpecialistsApi);
  private readonly agentApi = inject(AgentApi);
  private readonly notifications = inject(Notifications);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly specialists = httpResource<SpecialistInfo[]>(() => this.api.listUrl, { defaultValue: [] });
  protected readonly tools = httpResource<ToolInfo[]>(() => this.agentApi.url('/tools'), { defaultValue: [] });
  protected readonly editable = computed(() => this.specialists.value().filter((s) => !s.builtIn));
  protected readonly general = computed(() => this.specialists.value().find((s) => s.builtIn) ?? null);
  /** Installed tools that are not external (MCP tools are chosen with a pattern such as `mcp_*`). */
  protected readonly localTools = computed(() => this.tools.value().filter((t) => !t.external));

  /** Id of the specialist being edited, 'new' while creating one, null when the editor is closed. */
  protected readonly editing = signal<string | null>(null);
  protected readonly saving = signal(false);

  /** One checkbox per installed (non-external) tool, rebuilt when the editor opens. */
  private readonly toolChecks: FormRecord<FormControl<boolean>> = this.fb.record<boolean>({});

  protected readonly form = this.fb.group({
    id: ['', [Validators.required, Validators.pattern(/^[a-z][a-z0-9-]{1,39}$/)]],
    name: ['', [Validators.required, Validators.maxLength(100)]],
    description: ['', [Validators.required, Validators.maxLength(500)]],
    keywords: [''],
    prompt: ['', [Validators.required, Validators.maxLength(4000)]],
    allTools: [true],
    tools: this.toolChecks,
    extraTools: [''],
    examples: this.fb.array<FormGroup>([]),
    enabled: [true],
    sortOrder: [100, [Validators.required, Validators.min(0), Validators.max(10000)]],
  });

  protected readonly message = signal('');
  protected readonly previewing = signal(false);
  protected readonly preview = signal<RoutePreview | null>(null);
  /** Specialists of the last preview, by similarity, with their matched keywords. */
  protected readonly previewRows = computed(() => {
    const preview = this.preview();
    if (!preview) {
      return [];
    }
    const ids = new Set([...Object.keys(preview.similarities), ...Object.keys(preview.keywords)]);
    return [...ids]
      .map((id) => ({
        id,
        name: this.specialists.value().find((s) => s.id === id)?.name ?? id,
        keywords: preview.keywords[id] ?? [],
        similarity: preview.similarities[id] ?? null,
      }))
      .sort((a, b) => (b.similarity ?? -1) - (a.similarity ?? -1));
  });

  protected get examples(): FormArray<FormGroup> {
    return this.form.controls.examples;
  }

  protected methodKey(preview: RoutePreview): TranslationKey {
    return `specialists.method.${preview.method}`;
  }

  protected toolSummary(specialist: SpecialistInfo): string {
    return specialist.allowedTools === null
      ? this.i18n.t('specialists.allTools')
      : this.i18n.t('specialists.toolCount', { count: specialist.tools.length });
  }

  protected create(): void {
    this.open(null);
  }

  protected edit(specialist: SpecialistInfo): void {
    this.open(specialist);
  }

  protected close(): void {
    this.editing.set(null);
  }

  protected addExample(question = '', approach = ''): void {
    this.examples.push(
      this.fb.group({
        question: [question, [Validators.required, Validators.maxLength(300)]],
        approach: [approach, [Validators.required, Validators.maxLength(500)]],
      }),
    );
  }

  protected removeExample(index: number): void {
    this.examples.removeAt(index);
  }

  protected async save(): Promise<void> {
    const id = this.editing();
    if (!id || this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const request = this.request();
    this.saving.set(true);
    try {
      const saved = await firstValueFrom(id === 'new' ? this.api.create(request) : this.api.update(id, request));
      this.notifications.info(this.i18n.t('specialists.saved', { name: saved.name }));
      this.editing.set(null);
      this.specialists.reload();
    } catch {
      // Toast shown by the error interceptor (400 with the reason, 409 for a taken id).
    } finally {
      this.saving.set(false);
    }
  }

  protected async remove(specialist: SpecialistInfo): Promise<void> {
    if (!confirm(this.i18n.t('specialists.confirmDelete', { name: specialist.name }))) {
      return;
    }
    try {
      await firstValueFrom(this.api.delete(specialist.id));
      if (this.editing() === specialist.id) {
        this.editing.set(null);
      }
      this.specialists.reload();
    } catch {
      // Toast shown by the error interceptor.
    }
  }

  protected async restoreDefaults(): Promise<void> {
    if (!confirm(this.i18n.t('specialists.confirmRestore'))) {
      return;
    }
    try {
      await firstValueFrom(this.api.restoreDefaults());
      this.notifications.info(this.i18n.t('specialists.restored'));
      this.editing.set(null);
      this.specialists.reload();
    } catch {
      // Toast shown by the error interceptor.
    }
  }

  protected async tryRouting(): Promise<void> {
    const message = this.message().trim();
    if (!message || this.previewing()) {
      return;
    }
    this.previewing.set(true);
    try {
      this.preview.set(await firstValueFrom(this.api.preview(message)));
    } catch {
      // Toast shown by the error interceptor.
    } finally {
      this.previewing.set(false);
    }
  }

  private open(specialist: SpecialistInfo | null): void {
    const patterns = specialist?.allowedTools ?? null;
    const local = new Set(this.localTools().map((t) => t.name));
    this.form.reset({
      id: specialist?.id ?? '',
      name: specialist?.name ?? '',
      description: specialist?.description ?? '',
      keywords: (specialist?.keywords ?? []).join(', '),
      prompt: specialist?.prompt ?? '',
      allTools: patterns === null,
      extraTools: (patterns ?? []).filter((p) => !local.has(p)).join(', '),
      enabled: specialist?.enabled ?? true,
      sortOrder: specialist?.sortOrder ?? 100,
    });
    Object.keys(this.toolChecks.controls).forEach((name) => this.toolChecks.removeControl(name));
    for (const tool of this.localTools()) {
      this.toolChecks.addControl(tool.name, this.fb.control(patterns === null || patterns.includes(tool.name)));
    }
    this.examples.clear();
    (specialist?.exampleList ?? []).forEach((e) => this.addExample(e.question, e.approach));
    if (specialist) {
      this.form.controls.id.disable();
    } else {
      this.form.controls.id.enable();
    }
    this.editing.set(specialist?.id ?? 'new');
  }

  private request(): SpecialistRequest {
    const v = this.form.getRawValue();
    const tools = v.allTools
      ? null
      : [
          ...Object.entries(v.tools)
            .filter(([, checked]) => checked)
            .map(([name]) => name),
          ...parseKeywords(v.extraTools),
        ];
    return {
      id: v.id,
      name: v.name.trim(),
      description: v.description.trim(),
      keywords: parseKeywords(v.keywords),
      tools,
      prompt: v.prompt.trim(),
      examples: v.examples.map((e) => ({ question: String(e['question']).trim(), approach: String(e['approach']).trim() })),
      enabled: v.enabled,
      sortOrder: v.sortOrder,
    };
  }
}
