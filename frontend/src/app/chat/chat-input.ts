import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, NonNullableFormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';

import { ImageAttachment } from '../core/models';

/** Same default as the backend guardrail (agent.guardrails.max-input-chars). */
export const MAX_MESSAGE_LENGTH = 4000;
/** Same limits as the backend (ImageAttachment). */
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
export const IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp'];

export interface ChatSubmission {
  message: string;
  image: ImageAttachment | null;
}

function notBlank(control: AbstractControl<string>): ValidationErrors | null {
  return control.value.trim().length === 0 ? { blank: true } : null;
}

/** Message box; with `allowImages`, a screenshot can be attached with the button or pasted into the box. */
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
        (paste)="onPaste($event)"
      ></textarea>
      @if (image(); as attached) {
        <div class="attachment">
          <img [src]="'data:' + attached.mimeType + ';base64,' + attached.data" alt="Attached screenshot" />
          <span>Screenshot attached</span>
          <button type="button" class="link-button" (click)="image.set(null)">Remove</button>
        </div>
      }
      @if (imageError(); as error) {
        <p class="error" role="alert">{{ error }}</p>
      }
      <div class="composer-footer">
        <span class="hint" [class.over]="remaining() < 0" aria-live="polite">
          @if (remaining() < 200) {
            {{ remaining() }} characters left ·
          }
          Enter to send, Shift+Enter for a new line
          @if (allowImages()) {
            · paste or attach a screenshot
          }
        </span>
        <div class="actions">
          @if (allowImages()) {
            <input #file type="file" class="visually-hidden" [accept]="imageTypes" (change)="onFile(file)" />
            <button type="button" class="button" [disabled]="disabled()" (click)="file.click()" aria-label="Attach a screenshot">📎</button>
          }
          <button type="submit" class="button primary" [disabled]="form.invalid || disabled()">Send</button>
        </div>
      </div>
    </form>
  `,
  styles: `
    .composer { display: grid; gap: 0.5rem; }
    textarea { width: 100%; resize: vertical; min-height: 3.5rem; }
    .composer-footer { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
    .actions { display: flex; gap: 0.5rem; }
    .hint { font-size: 0.8rem; color: var(--text-muted); }
    .hint.over { color: var(--danger); }
    .attachment { display: flex; align-items: center; gap: 0.6rem; font-size: 0.85rem; }
    .attachment img { max-height: 3.5rem; max-width: 6rem; border-radius: var(--radius); border: 1px solid var(--border); }
    .error { margin: 0; color: var(--danger); font-size: 0.85rem; }
  `,
})
export class ChatInput {
  readonly disabled = input(false);
  readonly allowImages = input(false);
  readonly placeholder = input('Describe your IT problem…');
  readonly send = output<ChatSubmission>();

  protected readonly maxLength = MAX_MESSAGE_LENGTH;
  protected readonly imageTypes = IMAGE_TYPES.join(',');
  protected readonly form = inject(NonNullableFormBuilder).group({
    message: ['', [Validators.required, Validators.maxLength(MAX_MESSAGE_LENGTH), notBlank]],
  });
  protected readonly image = signal<ImageAttachment | null>(null);
  protected readonly imageError = signal<string | null>(null);

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

  /** Puts a message (and its screenshot) back into the box, e.g. after the backend rejected it. */
  restore(submission: ChatSubmission): void {
    this.form.setValue({ message: submission.message });
    this.image.set(submission.image);
  }

  /** Attaches an image file after checking its type and size; @returns false if it was refused */
  async attach(file: File): Promise<boolean> {
    this.imageError.set(null);
    if (!IMAGE_TYPES.includes(file.type)) {
      this.imageError.set('Only PNG, JPEG or WebP screenshots can be attached.');
      return false;
    }
    if (file.size > MAX_IMAGE_BYTES) {
      this.imageError.set('The screenshot is larger than 5 MB.');
      return false;
    }
    this.image.set({ mimeType: file.type, data: await toBase64(file) });
    return true;
  }

  protected onFile(input: HTMLInputElement): void {
    const file = input.files?.[0];
    if (file) {
      void this.attach(file);
    }
    input.value = '';
  }

  protected onPaste(event: ClipboardEvent): void {
    if (!this.allowImages()) {
      return;
    }
    const file = Array.from(event.clipboardData?.files ?? []).find((f) => f.type.startsWith('image/'));
    if (file) {
      event.preventDefault();
      void this.attach(file);
    }
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
    this.send.emit({ message: this.form.getRawValue().message.trim(), image: this.image() });
    this.form.reset();
    this.image.set(null);
    this.imageError.set(null);
  }
}

async function toBase64(file: Blob): Promise<string> {
  const bytes = new Uint8Array(await file.arrayBuffer());
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  return btoa(binary);
}
