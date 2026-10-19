import { Injectable, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';
import {
  CanDeactivateFn,
  RedirectCommand,
  ResolveFn,
  Router,
  RouterStateSnapshot,
  TitleStrategy,
} from '@angular/router';
import { catchError, of } from 'rxjs';

import { AgentApi } from './agent-api';
import { ChatStore } from './chat-store';
import { Run } from './models';

/** Loads the run before the details page renders; unknown ids go to the 404 page. */
export const runResolver: ResolveFn<Run> = (route) => {
  const router = inject(Router);
  return inject(AgentApi)
    .getRun(route.paramMap.get('runId')!)
    .pipe(catchError(() => of(new RedirectCommand(router.parseUrl('/not-found')))));
};

/** Warns before leaving the chat while the agent is still working on a request. */
export const leaveWhileBusyGuard: CanDeactivateFn<unknown> = () => {
  const store = inject(ChatStore);
  return !store.sending() || confirm('The agent is still working on your request. Leave anyway? The answer will appear when you come back.');
};

/** Implemented by pages with a form, so leaving them does not silently drop edits. */
export interface HasUnsavedChanges {
  hasUnsavedChanges(): boolean;
}

export const unsavedChangesGuard: CanDeactivateFn<HasUnsavedChanges> = (component) =>
  !component.hasUnsavedChanges() || confirm('You have unsaved changes. Leave without saving?');

/** Prefixes every route title with the app name. */
@Injectable({ providedIn: 'root' })
export class AppTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);

  override updateTitle(snapshot: RouterStateSnapshot): void {
    const pageTitle = this.buildTitle(snapshot);
    this.title.setTitle(pageTitle ? `${pageTitle} · Helpdesk Agent` : 'Helpdesk Agent');
  }
}
