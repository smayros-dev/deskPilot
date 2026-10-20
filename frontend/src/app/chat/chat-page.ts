import { ChangeDetectionStrategy, Component, OnInit, computed, effect, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService } from '../core/auth';
import { ChatStore } from '../core/chat-store';
import { I18n, TranslationKey } from '../core/i18n';
import { ApprovalDecision, Run } from '../core/models';
import { SettingsStore } from '../core/settings-store';
import { RunTrace } from '../runs/run-trace';
import { Answer } from '../shared/answer';
import { AutoScroll } from '../shared/auto-scroll';
import { DurationPipe } from '../shared/duration-pipe';
import { FeedbackButtons } from '../shared/feedback-buttons';
import { SpecialistBadge, specialistLabel } from '../shared/specialist-badge';
import { StatusBadge } from '../shared/status-badge';
import { ApprovalCard } from './approval-card';
import { ChatInput, ChatSubmission } from './chat-input';
import { ConversationList } from './conversation-list';
import { ModelSwitcher } from './model-switcher';

const EXAMPLES: TranslationKey[] = ['chat.example1', 'chat.example2', 'chat.example3', 'chat.example4', 'chat.example5'];

@Component({
  selector: 'app-chat-page',
  imports: [ConversationList, FeedbackButtons, RouterLink, ChatInput, ApprovalCard, ModelSwitcher, RunTrace, StatusBadge, AutoScroll, DurationPipe, Answer, SpecialistBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './chat-page.html',
  styleUrl: './chat-page.css',
})
export class ChatPage implements OnInit {
  protected readonly store = inject(ChatStore);
  protected readonly auth = inject(AuthService);
  protected readonly i18n = inject(I18n);
  private readonly settings = inject(SettingsStore);
  /** Screenshots can be attached once an admin has chosen a vision model in Settings. */
  protected readonly allowImages = computed(() => !!this.settings.settings()?.visionModel);
  protected readonly examples = EXAMPLES;

  private readonly chatInput = viewChild.required(ChatInput);

  /** Runs whose trace panel is open. */
  protected readonly expanded = signal<ReadonlySet<string>>(new Set());
  /** Seconds since the current request started, shown while the (slow, local) model works. */
  protected readonly elapsedSeconds = signal(0);
  protected readonly scrollTrigger = computed(() => `${this.store.runs().length}:${this.store.sending()}:${this.store.lastRun()?.status}`);

  constructor() {
    effect((onCleanup) => {
      if (!this.store.sending()) {
        return;
      }
      this.elapsedSeconds.set(0);
      const timer = setInterval(() => this.elapsedSeconds.update((s) => s + 1), 1000);
      onCleanup(() => clearInterval(timer));
    });
  }

  ngOnInit(): void {
    if (!this.settings.settings()) {
      void this.settings.load();
    }
    void this.store.init();
  }

  protected specialistName(id: string): string {
    return specialistLabel(this.i18n, id);
  }

  protected hasScreenshot(run: Run): boolean {
    return run.steps.some((step) => step.type === 'IMAGE_ANALYSIS');
  }

  protected async send(submission: ChatSubmission): Promise<void> {
    const accepted = await this.store.send(submission.message, submission.image);
    if (!accepted) {
      this.chatInput().restore(submission);
    }
  }

  protected decide(run: Run, decision: ApprovalDecision): void {
    void this.store.decide(run.id, decision);
  }

  protected toggleTrace(runId: string): void {
    this.expanded.update((ids) => {
      const next = new Set(ids);
      if (next.has(runId)) {
        next.delete(runId);
      } else {
        next.add(runId);
      }
      return next;
    });
  }

  protected newConversation(): void {
    void this.store.newConversation();
  }
}
