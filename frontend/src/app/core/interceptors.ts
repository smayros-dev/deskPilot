import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, finalize, throwError } from 'rxjs';

import { SILENT_ERRORS } from './api-config';
import { AuthService } from './auth';
import { ProblemDetail } from './models';
import { LoadingIndicator, Notifications } from './notifications';

export const loadingInterceptor: HttpInterceptorFn = (req, next) => {
  const loading = inject(LoadingIndicator);
  loading.start();
  return next(req).pipe(finalize(() => loading.stop()));
};

/** Turns backend Problem Details (and network failures) into a toast, then rethrows for the caller. */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const notifications = inject(Notifications);
  const auth = inject(AuthService);
  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401) {
        // The session at Keycloak ended (or the token could not be refreshed): sign in again.
        auth.login();
      } else if (!req.context.get(SILENT_ERRORS)) {
        notifications.error(problemMessage(error));
      }
      return throwError(() => error);
    }),
  );
};

export function problemMessage(error: HttpErrorResponse): string {
  if (error.status === 0) {
    return 'The backend is not reachable. Is Spring Boot running on port 8080?';
  }
  if (error.status === 403) {
    return 'You do not have permission to do this.';
  }
  if (error.status === 429) {
    const retryAfter = Number(error.headers?.get('Retry-After'));
    return Number.isFinite(retryAfter) && retryAfter > 0
      ? `You are sending messages too fast. Try again in ${retryAfter} s.`
      : 'You are sending messages too fast. Try again in a moment.';
  }
  if (error.status === 503) {
    const retryAfter = Number(error.headers?.get('Retry-After'));
    return Number.isFinite(retryAfter) && retryAfter > 0
      ? `The language model is unavailable right now. Try again in ${retryAfter} s.`
      : 'The language model is unavailable right now. Try again shortly.';
  }
  const problem = error.error as ProblemDetail | null;
  if (problem && typeof problem === 'object' && (problem.detail || problem.title)) {
    return problem.detail ?? problem.title ?? `Request failed (${error.status})`;
  }
  return `Request failed (${error.status} ${error.statusText})`;
}
