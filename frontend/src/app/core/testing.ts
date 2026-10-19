import { Provider, computed, signal } from '@angular/core';

import { AuthService, Role } from './auth';

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
