import { Directive, ElementRef, afterRenderEffect, inject, input } from '@angular/core';

/**
 * Keeps a scrollable element pinned to the bottom whenever `appAutoScroll` changes
 * (e.g. the number of messages), after Angular has rendered the new content.
 */
@Directive({ selector: '[appAutoScroll]' })
export class AutoScroll {
  readonly appAutoScroll = input<unknown>();

  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);

  constructor() {
    afterRenderEffect(() => {
      this.appAutoScroll();
      const el = this.element.nativeElement;
      el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
    });
  }
}
