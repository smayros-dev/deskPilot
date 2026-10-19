import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, finalize, throwError } from 'rxjs';

import { SILENT_ERRORS } from './api-config';
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
  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      if (!req.context.get(SILENT_ERRORS)) {
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
  const problem = error.error as ProblemDetail | null;
  if (problem && typeof problem === 'object' && (problem.detail || problem.title)) {
    return problem.detail ?? problem.title ?? `Request failed (${error.status})`;
  }
  return `Request failed (${error.status} ${error.statusText})`;
}
