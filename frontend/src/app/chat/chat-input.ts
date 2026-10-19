import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, NonNullableFormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';

/** Same default as the backend guardrail (agent.guardrails.max-input-chars). */
export const MAX_MESSAGE_LENGTH = 4000;

function notBlank(control: AbstractControl<string>): ValidationErrors | null {
  return control.value.trim().length === 0 ? { blank: true } : null;
}

@Component({
  selector: 'app-chat-input',
  imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" class="composer">
      <label for="message" class="visually-hidden">Message</label>
      <textarea
        id="message"
        formControlName="message"
        rows="2"
        [attr.maxlength]="maxLength"
        [placeholder]="placeholder()"
        (keydown.enter)="onEnter($any($event))"
      ></textarea>
      <div class="composer-footer">
        <span class="hint" [class.over]="remaining() < 0" aria-live="polite">
          @if (remaining() < 200) {
            {{ remaining() }} characters left ·
          }
          Enter to send, Shift+Enter for a new line
        </span>
        <button type="submit" class="button primary" [disabled]="form.invalid || disabled()">Send</button>
      </div>
    </form>
  `,
  styles: `
    .composer { display: grid; gap: 0.5rem; }
    textarea { width: 100%; resize: vertical; min-height: 3.5rem; }
    .composer-footer { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
    .hint { font-size: 0.8rem; color: var(--text-muted); }
    .hint.over { color: var(--danger); }
  `,
})
export class ChatInput {
  readonly disabled = input(false);
  readonly placeholder = input('Describe your IT problem…');
  readonly send = output<string>();

  protected readonly maxLength = MAX_MESSAGE_LENGTH;
  protected readonly form = inject(NonNullableFormBuilder).group({
    message: ['', [Validators.required, Validators.maxLength(MAX_MESSAGE_LENGTH), notBlank]],
  });

  private readonly message = toSignal(this.form.controls.message.valueChanges, { initialValue: '' });
  protected readonly remaining = computed(() => MAX_MESSAGE_LENGTH - this.message().length);

  constructor() {
    effect(() => {
      const control = this.form.controls.message;
      if (this.disabled()) {
        control.disable({ emitEvent: false });
      } else {
        control.enable({ emitEvent: false });
      }
    });
  }

  /** Puts a message back into the box, e.g. after the backend rejected it. */
  restore(message: string): void {
    this.form.setValue({ message });
  }

  protected onEnter(event: KeyboardEvent): void {
    if (!event.shiftKey) {
      event.preventDefault();
      this.submit();
    }
  }

  protected submit(): void {
    if (this.form.invalid || this.disabled()) {
      return;
    }
    this.send.emit(this.form.getRawValue().message.trim());
    this.form.reset();
  }
}
