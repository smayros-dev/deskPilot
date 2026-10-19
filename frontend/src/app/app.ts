import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { LoadingIndicator } from './core/notifications';
import { HealthPill } from './shared/health-pill';
import { ToastContainer } from './shared/toast-container';

interface NavLink {
  path: string;
  label: string;
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

  protected readonly links: NavLink[] = [
    { path: '/chat', label: 'Chat' },
    { path: '/dashboard', label: 'Dashboard' },
    { path: '/helpdesk/tickets', label: 'Tickets' },
    { path: '/helpdesk/knowledge', label: 'Knowledge base' },
    { path: '/helpdesk/memories', label: 'Memories' },
    { path: '/settings', label: 'Settings' },
  ];
}
