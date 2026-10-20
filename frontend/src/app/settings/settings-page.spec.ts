import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { errorInterceptor } from '../core/interceptors';
import { Role } from '../core/auth';
import { provideFakeAuth } from '../core/testing';
import { AgentSettings, ModelInfo, ToolInfo } from '../core/models';
import { SettingsPage } from './settings-page';

const SETTINGS: AgentSettings = {
  chatModel: 'qwen3:8b',
  temperature: 0.2,
  topP: 0.9,
  contextWindow: 8192,
  maxOutputTokens: 2048,
  thinking: false,
  maxIterations: 8,
  ragTopK: 3,
  ragSimilarityThreshold: 0.55,
  groundingCheck: true,
  hybridSearch: true,
  selfCheck: true,
  disabledTools: [],
  approvalTools: ['create_ticket'],
  systemPrompt: null,
  updatedAt: '2026-10-19T10:00:00Z',
};

const MODELS: ModelInfo[] = [
  { name: 'qwen3:8b', sizeBytes: 5.2e9, parameterSize: '8.2B', family: 'qwen3', capabilities: ['completion', 'tools', 'thinking'], supportsTools: true, supportsThinking: true, chatModel: true },
  { name: 'qwen3:4b', sizeBytes: 2.5e9, parameterSize: '4B', family: 'qwen3', capabilities: ['completion', 'tools'], supportsTools: true, supportsThinking: false, chatModel: true },
  { name: 'gemma:2b', sizeBytes: 1.7e9, parameterSize: '2B', family: 'gemma', capabilities: ['completion'], supportsTools: false, supportsThinking: false, chatModel: true },
  { name: 'nomic-embed-text:latest', sizeBytes: 2.7e8, parameterSize: '137M', family: 'nomic-bert', capabilities: ['embedding'], supportsTools: false, supportsThinking: false, chatModel: false },
];

const TOOLS: ToolInfo[] = [
  { name: 'search_knowledge_base', description: 'Search the KB', approvalByDefault: false, external: false },
  { name: 'create_ticket', description: 'Create a ticket', approvalByDefault: true, external: false },
  { name: 'mcp_github_create_issue', description: 'Create a GitHub issue', approvalByDefault: true, external: true },
];

describe('SettingsPage', () => {
  let http: HttpTestingController;

  async function render(roles: Role[] = ['user', 'admin']) {
    TestBed.configureTestingModule({
      imports: [SettingsPage],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        provideFakeAuth('admin', roles),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(SettingsPage);
    fixture.detectChanges();
    http.expectOne('/api/settings').flush(SETTINGS);
    http.expectOne('/api/models').flush(MODELS);
    http.expectOne('/api/tools').flush(TOOLS);
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  afterEach(() => http.verify());

  it('lists chat models only and disables those without tool calling', async () => {
    const { element } = await render();

    const options = Array.from(element.querySelectorAll<HTMLOptionElement>('#chatModel option'));
    expect(options.map((o) => o.value)).toEqual(['qwen3:8b', 'qwen3:4b', 'gemma:2b']);
    expect(options.find((o) => o.value === 'gemma:2b')!.disabled).toBe(true);
  });

  it('fills the form from the saved settings and starts pristine', async () => {
    const { fixture, element } = await render();

    expect(element.querySelector<HTMLSelectElement>('#chatModel')!.value).toBe('qwen3:8b');
    expect(fixture.componentInstance.hasUnsavedChanges()).toBe(false);
    expect(element.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(true);
  });

  it('saves the edited values, turning tool checkboxes into enabled/approval lists', async () => {
    const { fixture, element } = await render();
    const select = element.querySelector<HTMLSelectElement>('#chatModel')!;
    select.value = 'qwen3:4b';
    select.dispatchEvent(new Event('change'));
    const approval = element.querySelector<HTMLInputElement>('input[aria-label="Require approval for create_ticket"]')!;
    approval.click();
    const enabled = element.querySelector<HTMLInputElement>('input[aria-label="Enable search_knowledge_base"]')!;
    enabled.click();
    await fixture.whenStable();
    expect(fixture.componentInstance.hasUnsavedChanges()).toBe(true);

    element.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
    const req = http.expectOne({ method: 'PUT', url: '/api/settings' });

    expect(req.request.body).toEqual({
      ...SETTINGS,
      updatedAt: undefined,
      chatModel: 'qwen3:4b',
      disabledTools: ['search_knowledge_base'],
      approvalTools: ['mcp_github_create_issue'], // external tools always need approval
    });
    req.flush({ ...req.request.body, updatedAt: '2026-10-19T11:00:00Z' });
    await fixture.whenStable();
    expect(fixture.componentInstance.hasUnsavedChanges()).toBe(false);
  });

  it('presets change several values at once and only enable thinking when the model supports it', async () => {
    const { fixture, element } = await render();
    const select = element.querySelector<HTMLSelectElement>('#chatModel')!;
    select.value = 'qwen3:4b';
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    Array.from(element.querySelectorAll<HTMLButtonElement>('.presets button')).find((b) => b.textContent?.trim() === 'Thorough')!.click();
    await fixture.whenStable();

    expect(element.querySelector<HTMLInputElement>('input[formControlName=contextWindow]')!.value).toBe('16384');
    expect(element.querySelector<HTMLInputElement>('input[formControlName=thinking]')!.checked).toBe(false);
  });

  it('is read-only for users who are not admins', async () => {
    const { element } = await render(['user']);

    expect(element.querySelector<HTMLSelectElement>('#chatModel')!.disabled).toBe(true);
    expect(element.querySelector('button[type=submit]')).toBeNull();
    expect(element.querySelector('.notice')?.textContent).toContain('admin');
  });

  it('marks MCP tools and keeps their approval checked and locked', async () => {
    const { element } = await render();

    const approval = element.querySelector<HTMLInputElement>('input[aria-label="Require approval for mcp_github_create_issue"]')!;
    expect(approval.checked).toBe(true);
    expect(approval.disabled).toBe(true);
    expect(element.querySelector('tr:has(input[aria-label="Enable mcp_github_create_issue"]) .badge')?.textContent).toContain('MCP');
  });
});
