import { HttpContext, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { AgentApi } from './agent-api';
import { SILENT_ERRORS } from './api-config';
import { AuthService } from './auth';
import { ApprovalDecision, Run, Session } from './models';

const SESSION_KEY = 'agent.sessionId';
/** How often a run waiting for a reviewer is checked. */
export const APPROVAL_POLL_MS = 5000;

/**
 * Signal-based store for the conversation of the signed-in user: the session, its runs, and whether the agent
 * is working. Components read signals; all writes go through the methods below.
 */
@Injectable({ providedIn: 'root' })
export class ChatStore {
  private readonly api = inject(AgentApi);
  private readonly auth = inject(AuthService);

  private readonly _session = signal<Session | null>(null);
  private readonly _runs = signal<Run[]>([]);
  private readonly _sending = signal(false);

  readonly session = this._session.asReadonly();
  readonly runs = this._runs.asReadonly();
  readonly sending = this._sending.asReadonly();

  readonly lastRun = computed(() => this._runs().at(-1) ?? null);
  readonly awaitingApproval = computed(() => this.lastRun()?.status === 'AWAITING_APPROVAL');
  readonly canSend = computed(() => this._session() !== null && !this._sending() && !this.awaitingApproval());

  constructor() {
    effect(() => writeStorage(SESSION_KEY, this._session()?.id ?? null));
    // A reviewer may approve from another browser: keep checking the paused run until it moves on.
    effect((onCleanup) => {
      const run = this.lastRun();
      if (run?.status !== 'AWAITING_APPROVAL' || this._sending()) {
        return;
      }
      const timer = setInterval(() => void this.refresh(run.id), APPROVAL_POLL_MS);
      onCleanup(() => clearInterval(timer));
    });
  }

  /** Restores the user's last session in this browser (sessions are stored by the backend) or starts a new one. */
  async init(): Promise<void> {
    if (this._session()) {
      return;
    }
    const storedId = readStorage(SESSION_KEY);
    if (storedId) {
      try {
        const context = new HttpContext().set(SILENT_ERRORS, true);
        const session = await firstValueFrom(this.api.getSession(storedId, context));
        if (session.userId === this.auth.username()) {
          this._session.set(session);
          this._runs.set(await firstValueFrom(this.api.listRuns(session.id)));
          return;
        }
      } catch {
        // Another user's session (404) or a reset database: start a new one.
      }
    }
    await this.newConversation();
  }

  async newConversation(): Promise<void> {
    this._runs.set([]);
    this._session.set(await firstValueFrom(this.api.createSession()));
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

  private async refresh(runId: string): Promise<void> {
    try {
      const run = await firstValueFrom(this.api.getRun(runId, new HttpContext().set(SILENT_ERRORS, true)));
      if (run.status !== 'AWAITING_APPROVAL') {
        this.upsert(run);
      }
    } catch {
      // Try again on the next tick.
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
