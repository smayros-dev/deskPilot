import { Routes } from '@angular/router';

import { hasRole } from './core/auth';
import { leaveWhileBusyGuard, runResolver, unsavedChangesGuard } from './core/routing';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'chat' },
  {
    path: 'chat',
    title: 'title.chat',
    canDeactivate: [leaveWhileBusyGuard],
    loadComponent: () => import('./chat/chat-page').then((m) => m.ChatPage),
  },
  {
    path: 'runs/:runId',
    title: 'title.run',
    resolve: { run: runResolver },
    loadComponent: () => import('./runs/run-page').then((m) => m.RunPage),
  },
  {
    path: 'approvals',
    title: 'title.approvals',
    canMatch: [hasRole('reviewer')],
    loadComponent: () => import('./approvals/approvals-page').then((m) => m.ApprovalsPage),
  },
  {
    path: 'evaluation',
    title: 'title.evaluation',
    canMatch: [hasRole('admin')],
    loadComponent: () => import('./evaluation/evaluation-page').then((m) => m.EvaluationPage),
  },
  {
    path: 'audit',
    title: 'title.audit',
    canMatch: [hasRole('admin')],
    loadComponent: () => import('./audit/audit-page').then((m) => m.AuditPage),
  },
  {
    path: 'dashboard',
    title: 'title.dashboard',
    loadComponent: () => import('./dashboard/dashboard-page').then((m) => m.DashboardPage),
  },
  {
    path: 'settings',
    title: 'title.settings',
    canDeactivate: [unsavedChangesGuard],
    loadComponent: () => import('./settings/settings-page').then((m) => m.SettingsPage),
  },
  {
    path: 'helpdesk',
    loadChildren: () => import('./helpdesk/helpdesk.routes').then((m) => m.HELPDESK_ROUTES),
  },
  {
    path: '**',
    title: 'title.notFound',
    loadComponent: () => import('./not-found-page').then((m) => m.NotFoundPage),
  },
];
