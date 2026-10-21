import { ChangeDetectionStrategy, Component, computed, effect, inject, untracked } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { AuthService, Role } from './core/auth';
import { EventsService } from './core/events';
import { I18n, Lang, TranslationKey } from './core/i18n';
import { LoadingIndicator } from './core/notifications';
import { HealthPill } from './shared/health-pill';
import { ToastContainer } from './shared/toast-container';

interface NavLink {
  path: string;
  label: TranslationKey;
  /** Shown only to users with this role. */
  role?: Role;
}

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, HealthPill, ToastContainer],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly loading = inject(LoadingIndicator);
  protected readonly auth = inject(AuthService);
  protected readonly events = inject(EventsService);
  protected readonly i18n = inject(I18n);

  constructor() {
    // Live updates (runs, tickets, approval count) once the user is signed in.
    effect(() => {
      if (this.auth.isAuthenticated()) {
        untracked(() => this.events.start());
      }
    });
  }

  protected readonly links: NavLink[] = [
    { path: '/chat', label: 'nav.chat' },
    { path: '/approvals', label: 'nav.approvals', role: 'reviewer' },
    { path: '/dashboard', label: 'nav.dashboard' },
    { path: '/helpdesk/tickets', label: 'nav.tickets' },
    { path: '/helpdesk/knowledge', label: 'nav.knowledge' },
    { path: '/helpdesk/memories', label: 'nav.memories' },
    { path: '/evaluation', label: 'nav.evaluation', role: 'admin' },
    { path: '/experiments', label: 'nav.experiments', role: 'admin' },
    { path: '/specialists', label: 'nav.specialists', role: 'admin' },
    { path: '/audit', label: 'nav.audit', role: 'admin' },
    { path: '/settings', label: 'nav.settings' },
  ];

  protected readonly visibleLinks = computed(() => this.links.filter((link) => !link.role || this.auth.roles().has(link.role)));

  protected roleLabel(role: string): string {
    return role === 'admin' ? this.i18n.t('role.admin') : this.i18n.t('role.reviewer');
  }

  protected setLang(lang: string): void {
    this.i18n.setLang(lang as Lang);
  }
}
