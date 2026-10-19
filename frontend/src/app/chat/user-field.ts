import { ChangeDetectionStrategy, Component, model } from '@angular/core';

/**
 * Two-way bound user id (`[(userId)]`). Until authentication exists (Step 11) it decides whose
 * tickets and memories the agent works with.
 */
@Component({
  selector: 'app-user-field',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <label class="field">
      <span>User</span>
      <input
        #box
        [value]="userId()"
        maxlength="64"
        pattern="[A-Za-z0-9._@-]*"
        autocomplete="username"
        (keydown.enter)="box.blur()"
        (change)="commit(box.value)"
      />
    </label>
  `,
  styles: `
    .field { display: inline-flex; align-items: center; gap: 0.5rem; font-size: 0.85rem; color: var(--text-muted); }
    input { width: 10rem; padding: 0.3rem 0.5rem; }
  `,
})
export class UserField {
  readonly userId = model.required<string>();

  protected commit(value: string): void {
    const trimmed = value.trim();
    if (trimmed && /^[A-Za-z0-9._@-]+$/.test(trimmed)) {
      this.userId.set(trimmed);
    }
  }
}
