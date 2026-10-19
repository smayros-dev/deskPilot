import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { AgentApi } from './agent-api';
import { ApprovalDecision, Run, Session } from './models';

const USER_KEY = 'agent.userId';
const SESSION_KEY = 'agent.sessionId';
export const DEFAULT_USER = 'demo-user';

/**
 * Signal-based store for the conversation: the session, its runs, and whether the agent is working.
 * Components read signals; all writes go through the methods below.
 */
@Injectable({ providedIn: 'root' })
export class ChatStore {
  private readonly api = inject(AgentApi);

  private readonly _userId = signal(readStorage(USER_KEY) ?? DEFAULT_USER);
  private readonly _session = signal<Session | null>(null);
  private readonly _runs = signal<Run[]>([]);
  private readonly _sending = signal(false);

  readonly userId = this._userId.asReadonly();
  readonly session = this._session.asReadonly();
  readonly runs = this._runs.asReadonly();
  readonly sending = this._sending.asReadonly();

  readonly lastRun = computed(() => this._runs().at(-1) ?? null);
  readonly awaitingApproval = computed(() => this.lastRun()?.status === 'AWAITING_APPROVAL');
  readonly canSend = computed(() => this._session() !== null && !this._sending() && !this.awaitingApproval());

  constructor() {
    effect(() => writeStorage(USER_KEY, this._userId()));
    effect(() => writeStorage(SESSION_KEY, this._session()?.id ?? null));
  }

  /** Restores the last session of this browser (if the backend still knows it) or starts a new one. */
  async init(): Promise<void> {
    if (this._session()) {
      return;
    }
    const storedId = readStorage(SESSION_KEY);
    if (storedId) {
      try {
        const session = await firstValueFrom(this.api.getSession(storedId));
        if (session.userId === this._userId()) {
          this._session.set(session);
          this._runs.set(await firstValueFrom(this.api.listRuns(session.id)));
          return;
        }
      } catch {
        // Sessions live in backend memory and disappear on restart: fall through to a new one.
      }
    }
    await this.newConversation();
  }

  async newConversation(): Promise<void> {
    this._runs.set([]);
    this._session.set(await firstValueFrom(this.api.createSession(this._userId())));
  }

  async switchUser(userId: string): Promise<void> {
    const trimmed = userId.trim() || DEFAULT_USER;
    if (trimmed === this._userId()) {
      return;
    }
    this._userId.set(trimmed);
    await this.newConversation();
  }

  /** @returns false if the backend rejected the message (the error toast explains why) */
  async send(message: string): Promise<boolean> {
    const session = this._session();
    if (!session || !this.canSend()) {
      return false;
    }
    this._sending.set(true);
    try {
      this.upsert(await firstValueFrom(this.api.sendMessage(session.id, message)));
      return true;
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 404) {
        // Backend restarted and forgot the session.
        await this.newConversation();
      }
      return false;
    } finally {
      this._sending.set(false);
    }
  }

  async decide(runId: string, decision: ApprovalDecision): Promise<void> {
    this._sending.set(true);
    try {
      this.upsert(await firstValueFrom(this.api.decide(runId, decision)));
    } catch {
      // Toast shown by the error interceptor.
    } finally {
      this._sending.set(false);
    }
  }

  private upsert(run: Run): void {
    this._runs.update((runs) => {
      const index = runs.findIndex((r) => r.id === run.id);
      return index === -1 ? [...runs, run] : runs.map((r, i) => (i === index ? run : r));
    });
  }
}

// Storage can be unavailable (private mode, blocked cookies); the app must work without it.
function readStorage(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeStorage(key: string, value: string | null): void {
  try {
    if (value === null) {
      localStorage.removeItem(key);
    } else {
      localStorage.setItem(key, value);
    }
  } catch {
    // ignore
  }
}
