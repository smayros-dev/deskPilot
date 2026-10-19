import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { App } from './app';

describe('App', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideZonelessChangeDetection(), provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
  });

  it('renders the navigation', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/actuator/health').flush({ status: 'UP' });
    await fixture.whenStable();

    const links = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('nav a')).map((a) => a.textContent?.trim());
    expect(links).toEqual(['Chat', 'Dashboard', 'Tickets', 'Knowledge base', 'Memories', 'Settings']);
  });

  it('shows the model as online when Ollama is UP', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/actuator/health')
      .flush({ status: 'UP', components: { ollama: { status: 'UP' } } });
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).querySelector('app-health-pill')?.textContent).toContain('Model online');
  });
});
