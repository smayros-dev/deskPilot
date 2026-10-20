import { DatePipe, DecimalPipe, PercentPipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, InjectionToken, computed, effect, inject, linkedSignal, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { AgentApi } from '../core/agent-api';
import { I18n, TranslationKey } from '../core/i18n';
import { ModelInfo } from '../core/models';
import { Notifications } from '../core/notifications';
import { DurationPipe } from '../shared/duration-pipe';
import { Evaluation, EvaluationApi, EvaluationStatus, Scenario } from './evaluation-api';

/** How often a running evaluation is refreshed, in milliseconds. */
export const EVALUATION_POLL_MS = new InjectionToken<number>('EVALUATION_POLL_MS', {
  providedIn: 'root',
  factory: () => 3000,
});

/**
 * Admins run the scenario catalogue against an installed model and compare the scores of past evaluations
 * (pass rate overall and per category, latency), then drill into the scenarios that failed.
 */
@Component({
  selector: 'app-evaluation-page',
  imports: [DatePipe, DecimalPipe, PercentPipe, DurationPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './evaluation-page.html',
  styleUrl: './evaluation-page.css',
})
export class EvaluationPage {
  protected readonly i18n = inject(I18n);

  protected statusLabel(status: EvaluationStatus): TranslationKey {
    return `evalStatus.${status}`;
  }
  private readonly api = inject(EvaluationApi);
  private readonly agentApi = inject(AgentApi);
  private readonly notifications = inject(Notifications);
  private readonly pollMs = inject(EVALUATION_POLL_MS);

  protected readonly scenarios = httpResource<Scenario[]>(() => this.api.scenariosUrl, { defaultValue: [] });
  protected readonly models = httpResource<ModelInfo[]>(() => this.agentApi.url('/models'), { defaultValue: [] });
  protected readonly evaluations = httpResource<Evaluation[]>(() => this.api.listUrl, { defaultValue: [] });

  protected readonly toolModels = computed(() => this.models.value().filter((m) => m.chatModel && m.supportsTools));
  protected readonly categories = computed(() => [...new Set(this.scenarios.value().map((s) => s.category))]);

  /** Model to evaluate: the first tool-capable model until the user picks one. */
  protected readonly model = linkedSignal(() => this.toolModels()[0]?.name ?? '');
  /** Categories to run; none selected means all. */
  protected readonly selectedCategories = signal<ReadonlySet<string>>(new Set());
  protected readonly scenarioCount = computed(() => {
    const selected = this.selectedCategories();
    return this.scenarios.value().filter((s) => selected.size === 0 || selected.has(s.category)).length;
  });

  protected readonly running = computed(() => this.evaluations.value().find((e) => e.status === 'RUNNING') ?? null);
  protected readonly starting = signal(false);

  /** Evaluation shown in detail: the newest until the user picks another one. */
  protected readonly selectedId = linkedSignal(() => this.evaluations.value()[0]?.id ?? null);
  protected readonly detail = httpResource<Evaluation>(() => {
    const id = this.selectedId();
    return id ? this.api.detailUrl(id) : undefined;
  });
  protected readonly showOnlyFailures = signal(false);
  protected readonly visibleResults = computed(() => {
    const results = this.detail.hasValue() ? this.detail.value().results : [];
    return this.showOnlyFailures() ? results.filter((r) => !r.passed) : results;
  });

  /** Categories of all listed evaluations, for the comparison columns. */
  protected readonly comparedCategories = computed(() =>
    [...new Set(this.evaluations.value().flatMap((e) => e.categories.map((c) => c.category)))].sort(),
  );

  constructor() {
    // While an evaluation runs, refresh the list and the open detail.
    effect((onCleanup) => {
      if (!this.running()) {
        return;
      }
      const timer = setInterval(() => {
        this.evaluations.reload();
        this.detail.reload();
      }, this.pollMs);
      onCleanup(() => clearInterval(timer));
    });
  }

  protected toggleCategory(category: string): void {
    this.selectedCategories.update((selected) => {
      const next = new Set(selected);
      if (next.has(category)) {
        next.delete(category);
      } else {
        next.add(category);
      }
      return next;
    });
  }

  protected categoryScore(evaluation: Evaluation, category: string): string {
    const score = evaluation.categories.find((c) => c.category === category);
    return score ? `${score.passed}/${score.scenarios}` : '–';
  }

  protected async start(): Promise<void> {
    this.starting.set(true);
    try {
      const evaluation = await firstValueFrom(this.api.start(this.model(), [...this.selectedCategories()]));
      this.notifications.info(this.i18n.t('evaluation.started', { model: evaluation.model, count: evaluation.scenarioCount }));
      this.evaluations.reload();
      this.selectedId.set(evaluation.id);
    } catch {
      // Toast shown by the error interceptor.
    } finally {
      this.starting.set(false);
    }
  }

  protected async cancel(evaluation: Evaluation): Promise<void> {
    try {
      await firstValueFrom(this.api.cancel(evaluation.id));
      this.notifications.info(this.i18n.t('evaluation.cancelled'));
      this.evaluations.reload();
    } catch {
      // Toast shown by the error interceptor.
    }
  }
}
