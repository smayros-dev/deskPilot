import { DatePipe, NgTemplateOutlet, PercentPipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { parseModelChoice } from '../core/cloud';
import { Experiment, ExperimentsApi, PromptVersion, Significance, VariantRequest } from '../core/experiments';
import { I18n, TranslationKey } from '../core/i18n';
import { ModelOptions } from '../core/model-options';
import { Notifications } from '../core/notifications';
import { DurationPipe } from '../shared/duration-pipe';

/**
 * A/B experiments (12m, admins): split new conversations between two variants (model and/or prompt version),
 * compare their success rate, latency and feedback, and manage the prompt versions they use.
 */
@Component({
  selector: 'app-experiments-page',
  imports: [DatePipe, NgTemplateOutlet, PercentPipe, DurationPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './experiments-page.html',
  styleUrl: './experiments-page.css',
})
export class ExperimentsPage {
  protected readonly i18n = inject(I18n);
  private readonly api = inject(ExperimentsApi);
  private readonly notifications = inject(Notifications);
  protected readonly options = inject(ModelOptions);

  protected readonly experiments = httpResource<Experiment[]>(() => this.api.experimentsUrl, { defaultValue: [] });
  protected readonly prompts = httpResource<PromptVersion[]>(() => this.api.promptsUrl, { defaultValue: [] });

  protected readonly running = computed(() => this.experiments.value().find((e) => e.status === 'RUNNING') ?? null);
  protected readonly past = computed(() => this.experiments.value().filter((e) => e.status !== 'RUNNING'));
  /** Models that can call tools, grouped by provider. */
  protected readonly groups = computed(() =>
    this.options.groups().map((g) => ({ ...g, options: g.options.filter((o) => o.supportsTools) })),
  );
  private readonly firstModel = computed(() => this.groups()[0]?.options[0]?.value ?? '');

  protected readonly name = signal('');
  protected readonly modelA = linkedSignal(() => this.firstModel());
  protected readonly modelB = linkedSignal(() => this.firstModel());
  /** Prompt version id as select value; '' = the prompt from the settings. */
  protected readonly promptA = signal('');
  protected readonly promptB = signal('');
  protected readonly bPercent = signal(50);
  protected readonly starting = signal(false);

  protected readonly identical = computed(() => this.modelA() === this.modelB() && this.promptA() === this.promptB());
  protected readonly canStart = computed(
    () =>
      !this.running() &&
      !this.starting() &&
      this.name().trim().length > 0 &&
      !!this.modelA() &&
      !!this.modelB() &&
      !this.identical() &&
      this.bPercent() >= 1 &&
      this.bPercent() <= 99,
  );

  protected readonly promptName = signal('');
  protected readonly promptContent = signal('');
  protected readonly savingPrompt = signal(false);

  /** The variant that leads on success rate, only once both have finished runs. */
  protected leader(experiment: Experiment): string | null {
    const [a, b] = experiment.variants;
    if (!a || !b || a.finished === 0 || b.finished === 0 || a.successRate === b.successRate) {
      return null;
    }
    return a.successRate > b.successRate ? 'A' : 'B';
  }

  protected verdictKey(significance: Significance): TranslationKey {
    return `experiments.verdict.${significance.verdict}`;
  }

  protected testLabel(significance: Significance): string {
    return this.i18n.t(significance.test === 'fisher' ? 'experiments.testFisher' : 'experiments.testZ');
  }

  protected modelLabel(provider: string, model: string): string {
    return provider === 'ollama' ? model : `${this.options.providerLabel(provider)} · ${model}`;
  }

  protected async start(): Promise<void> {
    if (!this.canStart()) {
      return;
    }
    this.starting.set(true);
    try {
      const experiment = await firstValueFrom(
        this.api.start({
          name: this.name().trim(),
          a: this.variant(this.modelA(), this.promptA()),
          b: this.variant(this.modelB(), this.promptB()),
          bPercent: this.bPercent(),
        }),
      );
      this.notifications.info(this.i18n.t('experiments.started', { name: experiment.name }));
      this.name.set('');
      this.experiments.reload();
    } catch {
      // Toast shown by the error interceptor.
    } finally {
      this.starting.set(false);
    }
  }

  protected async stop(experiment: Experiment): Promise<void> {
    if (!confirm(this.i18n.t('experiments.confirmStop', { name: experiment.name }))) {
      return;
    }
    try {
      await firstValueFrom(this.api.stop(experiment.id));
      this.experiments.reload();
    } catch {
      // Toast shown by the error interceptor.
    }
  }

  protected async savePrompt(): Promise<void> {
    const name = this.promptName().trim();
    const content = this.promptContent().trim();
    if (!name || !content) {
      return;
    }
    this.savingPrompt.set(true);
    try {
      await firstValueFrom(this.api.createPrompt(name, content));
      this.notifications.info(this.i18n.t('prompts.saved', { name }));
      this.promptName.set('');
      this.promptContent.set('');
      this.prompts.reload();
    } catch {
      // Toast shown by the error interceptor.
    } finally {
      this.savingPrompt.set(false);
    }
  }

  protected async deletePrompt(prompt: PromptVersion): Promise<void> {
    if (!confirm(this.i18n.t('prompts.confirmDelete', { name: prompt.name }))) {
      return;
    }
    try {
      await firstValueFrom(this.api.deletePrompt(prompt.id));
      this.prompts.reload();
    } catch {
      // Toast shown by the error interceptor (409 when an experiment uses it).
    }
  }

  private variant(choice: string, prompt: string): VariantRequest {
    const { provider, model } = parseModelChoice(choice);
    return { provider, model, promptId: prompt ? Number(prompt) : null };
  }
}
