import { Injectable, effect, inject, signal } from '@angular/core';
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
import { I18n, TranslationKey } from './i18n';
import { EN } from './i18n/en';
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
  return !store.sending() || confirm(inject(I18n).t('guard.leaveBusy'));
};

/** Implemented by pages with a form, so leaving them does not silently drop edits. */
export interface HasUnsavedChanges {
  hasUnsavedChanges(): boolean;
}

export const unsavedChangesGuard: CanDeactivateFn<HasUnsavedChanges> = (component) =>
  !component.hasUnsavedChanges() || confirm(inject(I18n).t('guard.unsaved'));

/** Route titles are translation keys; they are shown in the current language and follow language changes. */
@Injectable({ providedIn: 'root' })
export class AppTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);
  private readonly i18n = inject(I18n);
  private readonly pageKey = signal<string | undefined>(undefined);

  constructor() {
    super();
    effect(() => {
      const key = this.pageKey();
      const page = key && key in EN ? this.i18n.t(key as TranslationKey) : key;
      this.title.setTitle(page ? `${page} · DeskPilot` : 'DeskPilot');
    });
  }

  override updateTitle(snapshot: RouterStateSnapshot): void {
    this.pageKey.set(this.buildTitle(snapshot));
  }
}
