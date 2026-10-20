import { Provider, computed, signal } from '@angular/core';
import { Subject } from 'rxjs';

import { AuthService, Role } from './auth';
import { EventsService } from './events';
import { Run, Ticket } from './models';

/** Replaces {@link AuthService} in tests with a signed-in user, without Keycloak. */
export function provideFakeAuth(username = 'alice', roles: Role[] = ['user']): Provider {
  const claims = signal({ username, roles: new Set<string>(roles) });
  const fake: Partial<AuthService> = {
    username: computed(() => claims().username),
    displayName: computed(() => claims().username),
    roles: computed(() => claims().roles),
    isAuthenticated: computed(() => true),
    isReviewer: computed(() => claims().roles.has('reviewer')),
    isAdmin: computed(() => claims().roles.has('admin')),
    error: signal(null).asReadonly(),
    hasRole: (role: Role) => claims().roles.has(role),
    accessToken: () => 'test-token',
    login: () => undefined,
    logout: () => undefined,
  };
  return { provide: AuthService, useValue: fake };
}

/** Stand-in for the live event stream: tests push events and flip the connection state themselves. */
export class FakeEvents {
  readonly connected = signal(false);
  readonly pendingApprovals = signal<number | null>(null);
  readonly runs = new Subject<Run>();
  readonly tickets = new Subject<Ticket>();
  readonly runs$ = this.runs.asObservable();
  readonly tickets$ = this.tickets.asObservable();
  started = false;

  start(): void {
    this.started = true;
  }

  stop(): void {
    this.started = false;
  }
}

export function provideFakeEvents(fake = new FakeEvents()): Provider {
  return { provide: EventsService, useValue: fake };
}
