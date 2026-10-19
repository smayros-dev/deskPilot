import { Routes } from '@angular/router';

/** Lazy-loaded section with the data the agent works on. */
export const HELPDESK_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'tickets' },
  {
    path: 'tickets',
    title: 'Tickets',
    loadComponent: () => import('./tickets-page').then((m) => m.TicketsPage),
  },
  {
    path: 'knowledge',
    title: 'Knowledge base',
    loadComponent: () => import('./knowledge-page').then((m) => m.KnowledgePage),
  },
  {
    path: 'memories',
    title: 'Memories',
    loadComponent: () => import('./memories-page').then((m) => m.MemoriesPage),
  },
];
