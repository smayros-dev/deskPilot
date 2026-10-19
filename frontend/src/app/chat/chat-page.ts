import { ChangeDetectionStrategy, Component, OnInit, computed, effect, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ChatStore } from '../core/chat-store';
import { ApprovalDecision, Run } from '../core/models';
import { RunTrace } from '../runs/run-trace';
import { AutoScroll } from '../shared/auto-scroll';
import { DurationPipe } from '../shared/duration-pipe';
import { StatusBadge } from '../shared/status-badge';
import { ApprovalCard } from './approval-card';
import { ChatInput } from './chat-input';
import { ModelSwitcher } from './model-switcher';
import { UserField } from './user-field';

const EXAMPLES = [
  'My VPN disconnects every few minutes, what should I do?',
  'How do I add the printer PRN-3-02?',
  'I lost my phone with the authenticator app on it.',
  'Please open a ticket: my laptop screen is cracked and I need a replacement.',
  'Remember that I work on a MacBook.',
];

@Component({
  selector: 'app-chat-page',
  imports: [RouterLink, ChatInput, ApprovalCard, UserField, ModelSwitcher, RunTrace, StatusBadge, AutoScroll, DurationPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './chat-page.html',
  styleUrl: './chat-page.css',
})
export class ChatPage implements OnInit {
  protected readonly store = inject(ChatStore);
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
    void this.store.init();
  }

  protected async send(message: string): Promise<void> {
    const accepted = await this.store.send(message);
    if (!accepted) {
      this.chatInput().restore(message);
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

  protected switchUser(userId: string): void {
    void this.store.switchUser(userId);
  }

  protected newConversation(): void {
    void this.store.newConversation();
  }
}
