import { ComponentRef, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { ChatInput, ChatSubmission, MAX_IMAGE_BYTES } from './chat-input';

describe('ChatInput', () => {
  let component: ChatInput;
  let ref: ComponentRef<ChatInput>;
  let element: HTMLElement;
  let sent: ChatSubmission[];

  async function render(allowImages: boolean) {
    TestBed.configureTestingModule({ imports: [ChatInput], providers: [provideZonelessChangeDetection()] });
    const fixture = TestBed.createComponent(ChatInput);
    ref = fixture.componentRef;
    ref.setInput('allowImages', allowImages);
    component = fixture.componentInstance;
    sent = [];
    component.send.subscribe((submission) => sent.push(submission));
    await fixture.whenStable();
    element = fixture.nativeElement as HTMLElement;
    return fixture;
  }

  function type(message: string) {
    const textarea = element.querySelector<HTMLTextAreaElement>('textarea')!;
    textarea.value = message;
    textarea.dispatchEvent(new Event('input'));
  }

  it('hides the attach button when screenshots are off', async () => {
    await render(false);

    expect(element.querySelector('[aria-label="Attach a screenshot"]')).toBeNull();
  });

  it('sends the message together with an attached screenshot, then clears both', async () => {
    const fixture = await render(true);
    const png = new File([new Uint8Array([137, 80, 78, 71])], 'error.png', { type: 'image/png' });

    expect(await component.attach(png)).toBe(true);
    type('What does this error mean?');
    await fixture.whenStable();
    expect(element.querySelector('.attachment img')).not.toBeNull();

    element.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
    await fixture.whenStable();

    expect(sent).toEqual([{ message: 'What does this error mean?', image: { mimeType: 'image/png', data: btoa('\x89PNG') } }]);
    expect(element.querySelector('.attachment')).toBeNull();
  });

  it('refuses other file types and files over 5 MB', async () => {
    const fixture = await render(true);

    expect(await component.attach(new File(['GIF89a'], 'anim.gif', { type: 'image/gif' }))).toBe(false);
    await fixture.whenStable();
    expect(element.querySelector('.error')?.textContent).toContain('PNG, JPEG or WebP');

    const big = new File([new Uint8Array(MAX_IMAGE_BYTES + 1)], 'big.png', { type: 'image/png' });
    expect(await component.attach(big)).toBe(false);
    await fixture.whenStable();
    expect(element.querySelector('.error')?.textContent).toContain('5 MB');
  });

  it('puts a rejected message and its screenshot back', async () => {
    const fixture = await render(true);

    component.restore({ message: 'Try again', image: { mimeType: 'image/png', data: 'AAAA' } });
    await fixture.whenStable();

    expect(element.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('Try again');
    expect(element.querySelector('.attachment')).not.toBeNull();
  });
});
