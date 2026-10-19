import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { AuthService, Role } from './core/auth';
import { LoadingIndicator } from './core/notifications';
import { HealthPill } from './shared/health-pill';
import { ToastContainer } from './shared/toast-container';

interface NavLink {
  path: string;
  label: string;
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

  protected readonly links: NavLink[] = [
    { path: '/chat', label: 'Chat' },
    { path: '/approvals', label: 'Approvals', role: 'reviewer' },
    { path: '/dashboard', label: 'Dashboard' },
    { path: '/helpdesk/tickets', label: 'Tickets' },
    { path: '/helpdesk/knowledge', label: 'Knowledge base' },
    { path: '/helpdesk/memories', label: 'My memories' },
    { path: '/settings', label: 'Settings' },
  ];

  protected readonly visibleLinks = computed(() => this.links.filter((link) => !link.role || this.auth.roles().has(link.role)));
}
