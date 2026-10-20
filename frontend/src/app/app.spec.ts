import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { App } from './app';
import { Role } from './core/auth';
import { FakeEvents, provideFakeAuth, provideFakeEvents } from './core/testing';

describe('App', () => {
  let events: FakeEvents;

  function setUp(username = 'alice', roles: Role[] = ['user']) {
    events = new FakeEvents();
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideFakeAuth(username, roles),
        provideFakeEvents(events),
      ],
    });
  }

  async function render() {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/actuator/health').flush({ status: 'UP' });
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  function navLinks(element: HTMLElement) {
    return Array.from(element.querySelectorAll('nav a')).map((a) => a.textContent?.trim());
  }

  it('renders the navigation and the signed-in user', async () => {
    setUp('alice');
    const element = await render();

    expect(navLinks(element)).toEqual(['Chat', 'Dashboard', 'Tickets', 'Knowledge base', 'My memories', 'Settings']);
    expect(element.querySelector('.user-menu')?.textContent).toContain('alice');
    expect(element.querySelector('.user-menu .role')).toBeNull();
  });

  it('shows the approvals queue and the role to reviewers', async () => {
    setUp('bob', ['user', 'reviewer']);
    const element = await render();

    expect(navLinks(element)).toContain('Approvals');
    expect(element.querySelector('.user-menu .role')?.textContent).toBe('reviewer');
  });

  it('opens the live event stream and shows the number of pending approvals to reviewers', async () => {
    setUp('bob', ['user', 'reviewer']);
    const element = await render();
    expect(events.started).toBe(true);
    expect(element.querySelector('nav .badge')).toBeNull();

    events.pendingApprovals.set(3);
    TestBed.tick();

    expect(element.querySelector('nav a[href="/approvals"] .badge')?.textContent?.trim()).toBe('3');
  });

  it('shows the model as online when Ollama is UP', async () => {
    setUp();
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/actuator/health')
      .flush({ status: 'UP', components: { ollama: { status: 'UP' } } });
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).querySelector('app-health-pill')?.textContent).toContain('Model online');
  });
});
