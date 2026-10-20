import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';

import { I18n, TranslationKey } from '../core/i18n';

const KNOWN = ['network', 'accounts', 'hardware', 'software', 'general'];

/** Name of a specialist agent (12f) in the current language; unknown ids are shown as they are. */
export function specialistLabel(i18n: I18n, id: string): string {
  return KNOWN.includes(id) ? i18n.t(`specialist.${id}` as TranslationKey) : id;
}

/** Badge naming the specialist agent that handled a message (12f). */
@Component({
  selector: 'app-specialist-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="badge" [title]="i18n.t('specialist.handledBy', { name: label() })">🧭 {{ label() }}</span>`,
  styles: `
    .badge { display: inline-flex; align-items: center; gap: 0.25rem; padding: 0.05rem 0.5rem; border-radius: 999px;
             border: 1px solid var(--border); background: var(--surface-muted); font-size: 0.75rem; white-space: nowrap; }
  `,
})
export class SpecialistBadge {
  protected readonly i18n = inject(I18n);
  readonly specialist = input.required<string>();
  protected readonly label = computed(() => specialistLabel(this.i18n, this.specialist()));
}
