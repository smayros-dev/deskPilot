import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { ApprovalDecision } from '../core/models';
import { ApprovalCard } from './approval-card';

describe('ApprovalCard', () => {
  async function render() {
    TestBed.configureTestingModule({ imports: [ApprovalCard], providers: [provideZonelessChangeDetection()] });
    const fixture = TestBed.createComponent(ApprovalCard);
    fixture.componentRef.setInput('approvals', [
      { toolCallId: 'c1', toolName: 'create_ticket', arguments: '{"title":"Laptop broken","priority":"HIGH"}' },
    ]);
    const decisions: ApprovalDecision[] = [];
    fixture.componentInstance.decide.subscribe((d) => decisions.push(d));
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement, decisions };
  }

  it('shows the tool call arguments as fields', async () => {
    const { element } = await render();

    expect(element.querySelector('.tool')?.textContent).toBe('create_ticket');
    expect(Array.from(element.querySelectorAll('dd')).map((dd) => dd.textContent)).toEqual(['Laptop broken', 'HIGH']);
  });

  it('emits an approval with the comment', async () => {
    const { element, decisions } = await render();
    const comment = element.querySelector<HTMLInputElement>('#comment')!;
    comment.value = '  looks good ';
    comment.dispatchEvent(new Event('input'));

    element.querySelector<HTMLButtonElement>('button[type=submit]')!.click();

    expect(decisions).toEqual([{ approved: true, comment: 'looks good' }]);
  });

  it('emits a rejection without comment', async () => {
    const { element, decisions } = await render();

    element.querySelector<HTMLButtonElement>('button.danger')!.click();

    expect(decisions).toEqual([{ approved: false, comment: null }]);
  });

  it('disables the buttons while busy', async () => {
    const { fixture, element } = await render();
    fixture.componentRef.setInput('busy', true);
    await fixture.whenStable();

    expect(Array.from(element.querySelectorAll('button')).every((b) => b.disabled)).toBe(true);
  });
});
