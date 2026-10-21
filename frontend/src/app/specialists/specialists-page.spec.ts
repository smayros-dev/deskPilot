import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { errorInterceptor } from '../core/interceptors';
import { ToolInfo } from '../core/models';
import { RoutePreview, SpecialistInfo } from '../core/specialists';
import { provideFakeAuth } from '../core/testing';
import { SpecialistsPage, parseKeywords } from './specialists-page';

const TOOLS: ToolInfo[] = [
  { name: 'search_knowledge_base', description: 'Search the KB', approvalByDefault: false, external: false },
  { name: 'create_ticket', description: 'Create a ticket', approvalByDefault: true, external: false },
  { name: 'mcp_github_create_issue', description: 'GitHub issue', approvalByDefault: true, external: true },
];

const NETWORK: SpecialistInfo = {
  id: 'network',
  name: 'Network & VPN',
  description: 'VPN and Wi-Fi problems.',
  keywords: ['vpn', 'wifi'],
  tools: ['create_ticket', 'search_knowledge_base'],
  examples: 1,
  enabled: true,
  builtIn: false,
  prompt: 'You are the network specialist.',
  allowedTools: ['search_knowledge_base', 'create_ticket'],
  exampleList: [{ question: 'My VPN drops', approach: 'Search the VPN article.' }],
  sortOrder: 10,
  updatedBy: 'system',
  updatedAt: '2026-10-21T08:00:00Z',
};

const GENERAL: SpecialistInfo = {
  ...NETWORK,
  id: 'general',
  name: 'General IT',
  description: 'Any other IT request.',
  keywords: [],
  tools: ['create_ticket', 'mcp_github_create_issue', 'search_knowledge_base'],
  examples: 0,
  builtIn: true,
  prompt: null,
  allowedTools: null,
  exampleList: [],
  sortOrder: null,
  updatedBy: null,
  updatedAt: null,
};

async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await Promise.resolve();
    TestBed.tick();
  }
}

describe('SpecialistsPage', () => {
  let http: HttpTestingController;

  async function render() {
    TestBed.configureTestingModule({
      imports: [SpecialistsPage],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        provideFakeAuth('admin', ['user', 'admin']),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(SpecialistsPage);
    fixture.detectChanges();
    http.expectOne('/api/specialists').flush([NETWORK, GENERAL]);
    http.expectOne('/api/tools').flush(TOOLS);
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  afterEach(() => http.verify());

  function click(element: HTMLElement, text: string): void {
    Array.from(element.querySelectorAll<HTMLButtonElement>('button'))
      .find((b) => b.textContent?.trim() === text)!
      .click();
  }

  function type(element: HTMLElement, selector: string, value: string): void {
    const input = element.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  it('lists the specialists with the general agent last, not editable', async () => {
    const { element } = await render();

    const rows = Array.from(element.querySelectorAll('table.list tbody tr'));
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('Network & VPN');
    expect(rows[0].textContent).toContain('vpn, wifi');
    expect(rows[0].textContent).toContain('2 tools');
    expect(rows[1].textContent).toContain('General IT');
    expect(rows[1].querySelector('button')).toBeNull();
  });

  it('edits a specialist: keywords as a comma list, tool checkboxes plus patterns', async () => {
    const { fixture, element } = await render();
    click(element, 'Edit');
    await fixture.whenStable();

    expect(element.querySelector<HTMLInputElement>('input[formControlName=id]')!.disabled).toBe(true);
    expect(element.querySelector<HTMLTextAreaElement>('textarea[formControlName=keywords]')!.value).toBe('vpn, wifi');
    // External MCP tools are not checkboxes: they are chosen with a pattern.
    expect(element.querySelectorAll('.tools input[type=checkbox]')).toHaveLength(2);
    type(element, 'textarea[formControlName=keywords]', 'vpn, wifi, VPN, réseau');
    type(element, 'input[formControlName=extraTools]', 'mcp_*');
    element.querySelector<HTMLInputElement>('.tools label:nth-child(2) input')!.click(); // create_ticket
    await fixture.whenStable();

    click(element, 'Save');
    const req = http.expectOne({ method: 'PUT', url: '/api/specialists/network' });
    expect(req.request.body).toEqual({
      id: 'network',
      name: 'Network & VPN',
      description: 'VPN and Wi-Fi problems.',
      keywords: ['vpn', 'wifi', 'réseau'],
      tools: ['search_knowledge_base', 'mcp_*'],
      prompt: 'You are the network specialist.',
      examples: [{ question: 'My VPN drops', approach: 'Search the VPN article.' }],
      enabled: true,
      sortOrder: 10,
    });
    req.flush({ ...NETWORK, keywords: ['vpn', 'wifi', 'réseau'] });
    await settle();
    http.expectOne('/api/specialists').flush([NETWORK, GENERAL]);
    await fixture.whenStable();
    expect(element.querySelector('.editor')).toBeNull();
  });

  it('creates a specialist with every tool and an example', async () => {
    const { fixture, element } = await render();
    click(element, 'New specialist');
    await fixture.whenStable();

    type(element, 'input[formControlName=id]', 'printing');
    type(element, 'input[formControlName=name]', 'Printing');
    type(element, 'input[formControlName=description]', 'Printers and toner.');
    type(element, 'textarea[formControlName=keywords]', 'toner,\ncartridge');
    type(element, 'textarea[formControlName=prompt]', 'You are the printing specialist.');
    click(element, 'Add an example');
    await fixture.whenStable();
    type(element, '.example input[formControlName=question]', 'Out of toner');
    type(element, '.example input[formControlName=approach]', 'Order a cartridge.');
    await fixture.whenStable();

    click(element, 'Save');
    const req = http.expectOne({ method: 'POST', url: '/api/specialists' });
    expect(req.request.body.id).toBe('printing');
    expect(req.request.body.keywords).toEqual(['toner', 'cartridge']);
    expect(req.request.body.tools).toBeNull();
    expect(req.request.body.examples).toEqual([{ question: 'Out of toner', approach: 'Order a cartridge.' }]);
    req.flush({ ...NETWORK, id: 'printing', name: 'Printing' });
    await settle();
    http.expectOne('/api/specialists').flush([NETWORK, GENERAL]);
  });

  it('does not send an invalid form', async () => {
    const { fixture, element } = await render();
    click(element, 'New specialist');
    await fixture.whenStable();
    type(element, 'input[formControlName=id]', 'Bad Id');
    await fixture.whenStable();

    click(element, 'Save');
    await fixture.whenStable();

    http.expectNone({ method: 'POST', url: '/api/specialists' });
    expect(element.querySelector('.editor [role=alert]')?.textContent).toContain('Check the highlighted fields');
  });

  it('shows where a message would be routed, with keyword hits and similarities', async () => {
    const { fixture, element } = await render();
    type(element, '#try-message', 'My VPN keeps dropping');
    await fixture.whenStable();
    click(element, 'Route');

    const req = http.expectOne({ method: 'POST', url: '/api/specialists/route-preview' });
    expect(req.request.body).toEqual({ message: 'My VPN keeps dropping' });
    const preview: RoutePreview = {
      specialist: 'network',
      name: 'Network & VPN',
      method: 'keywords',
      confidence: 1,
      detail: 'keywords: vpn',
      keywords: { network: ['vpn'] },
      similarities: { network: 0.81, accounts: 0.42 },
    };
    req.flush(preview);
    await fixture.whenStable();

    expect(element.querySelector('[data-testid=preview-specialist]')?.textContent).toBe('Network & VPN');
    expect(element.querySelector('.result')?.textContent).toContain('by keywords');
    const rows = Array.from(element.querySelectorAll('table.scores tbody tr'));
    expect(rows[0].classList).toContain('chosen');
    expect(rows[0].textContent).toContain('0.81');
    expect(rows[1].textContent).toContain('accounts');
  });

  it('deletes after confirmation', async () => {
    const { fixture, element } = await render();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    click(element, 'Delete');

    http.expectOne({ method: 'DELETE', url: '/api/specialists/network' }).flush(null);
    await settle();
    http.expectOne('/api/specialists').flush([GENERAL]);
    await fixture.whenStable();
    expect(confirm).toHaveBeenCalled();
    expect(element.querySelector('table.list')?.textContent).toContain('No specialist');
    confirm.mockRestore();
  });

  it('parses keywords separated by commas or new lines, dropping blanks and duplicates', () => {
    expect(parseKeywords(' vpn, Wi-Fi,\n vpn ,,WI-FI\nréseau ')).toEqual(['vpn', 'Wi-Fi', 'réseau']);
  });
});
