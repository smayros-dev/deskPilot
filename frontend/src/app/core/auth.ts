import { HttpInterceptorFn } from '@angular/common/http';
import { Injectable, InjectionToken, computed, inject, signal } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';
import { AuthConfig, OAuthService } from 'angular-oauth2-oidc';
import { filter } from 'rxjs';

import { API_CONFIG } from './api-config';
import { I18n } from './i18n';

export type Role = 'user' | 'reviewer' | 'admin';

/** OIDC client settings; the realm and client are defined in backend/keycloak/deskpilot-realm.json. */
export const AUTH_SETTINGS = new InjectionToken<AuthConfig>('AUTH_SETTINGS', {
  providedIn: 'root',
  factory: () => ({
    issuer: 'http://localhost:8180/realms/deskpilot',
    clientId: 'deskpilot-spa',
    responseType: 'code', // Authorization Code flow with PKCE
    redirectUri: `${window.location.origin}/`,
    postLogoutRedirectUri: `${window.location.origin}/`,
    scope: 'openid profile email',
    requireHttps: 'remoteOnly',
  }),
});

interface AccessTokenClaims {
  preferred_username?: string;
  name?: string;
  realm_access?: { roles?: string[] };
}

/**
 * The signed-in user as signals. Login is required: {@link init} (run before the first navigation) sends
 * anonymous visitors to Keycloak and comes back with tokens; access tokens are refreshed automatically.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly oauth = inject(OAuthService);
  private readonly settings = inject(AUTH_SETTINGS);
  private readonly i18n = inject(I18n);

  private readonly _claims = signal<AccessTokenClaims | null>(null);
  private readonly _error = signal<string | null>(null);

  readonly username = computed(() => this._claims()?.preferred_username ?? '');
  readonly displayName = computed(() => this._claims()?.name || this.username());
  readonly roles = computed(() => new Set(this._claims()?.realm_access?.roles ?? []));
  readonly isAuthenticated = computed(() => this._claims() !== null);
  readonly isReviewer = computed(() => this.hasRole('reviewer'));
  readonly isAdmin = computed(() => this.hasRole('admin'));
  /** Set when the identity provider cannot be reached. */
  readonly error = this._error.asReadonly();

  async init(): Promise<void> {
    this.oauth.configure(this.settings);
    this.oauth.events
      .pipe(filter((e) => e.type === 'token_received' || e.type === 'token_refreshed'))
      .subscribe(() => this.readToken());
    try {
      await this.oauth.loadDiscoveryDocumentAndLogin();
      this.oauth.setupAutomaticSilentRefresh();
      this.readToken();
    } catch {
      this._error.set(this.i18n.t('auth.cannotReach', { issuer: this.settings.issuer ?? '' }));
    }
  }

  hasRole(role: Role): boolean {
    return this.roles().has(role);
  }

  accessToken(): string | null {
    return this.oauth.hasValidAccessToken() ? this.oauth.getAccessToken() : null;
  }

  /** Back to Keycloak, e.g. when the backend rejected an expired session. */
  login(): void {
    this.oauth.initCodeFlow();
  }

  logout(): void {
    this.oauth.logOut();
  }

  private readToken(): void {
    this._claims.set(decodeJwtPayload<AccessTokenClaims>(this.oauth.getAccessToken()));
  }
}

/** Adds the access token to calls to our own backend only, never to third-party URLs. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const config = inject(API_CONFIG);
  const token = inject(AuthService).accessToken();
  const ownBackend = req.url.startsWith(config.baseUrl) || req.url.startsWith(config.actuatorUrl);
  return token && ownBackend ? next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })) : next(req);
};

/** Route guard factory: the route only matches for users with the role; others are sent to the chat. */
export function hasRole(role: Role): CanMatchFn {
  return () => inject(AuthService).hasRole(role) || inject(Router).createUrlTree(['/chat']);
}

function decodeJwtPayload<T>(token: string | null): T | null {
  const payload = token?.split('.')[1];
  if (!payload) {
    return null;
  }
  try {
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const json = new TextDecoder().decode(Uint8Array.from(atob(base64), (c) => c.charCodeAt(0)));
    return JSON.parse(json) as T;
  } catch {
    return null;
  }
}
