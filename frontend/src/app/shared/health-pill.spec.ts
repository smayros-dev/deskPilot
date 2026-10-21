import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { HealthPill } from './health-pill';

describe('HealthPill', () => {
  let http: HttpTestingController;

  async function render(respond: (http: HttpTestingController) => void) {
    TestBed.configureTestingModule({
      imports: [HealthPill],
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(HealthPill);
    fixture.detectChanges();
    respond(http);
    await fixture.whenStable();
    const pill = (fixture.nativeElement as HTMLElement).querySelector('.pill')!;
    return { text: pill.textContent?.trim(), state: pill.getAttribute('data-state'), title: pill.getAttribute('title') };
  }

  afterEach(() => http.verify());

  it('shows the model online when Ollama and the database are up', async () => {
    const pill = await render((h) =>
      h.expectOne('/actuator/health').flush({ status: 'UP', components: { db: { status: 'UP' }, ollama: { status: 'UP' } } }),
    );

    expect(pill.state).toBe('up');
    expect(pill.text).toBe('Model online');
  });

  it('blames the model only when Ollama is down', async () => {
    const pill = await render((h) =>
      h
        .expectOne('/actuator/health')
        .flush(
          { status: 'DOWN', components: { db: { status: 'UP' }, ollama: { status: 'DOWN' } } },
          { status: 503, statusText: 'Service Unavailable' },
        ),
    );

    expect(pill.state).toBe('model');
    expect(pill.text).toBe('Model offline');
    expect(pill.title).toContain('ollama serve');
  });

  it('reports the database when PostgreSQL is down, even if Ollama is up', async () => {
    const pill = await render((h) =>
      h
        .expectOne('/actuator/health')
        .flush(
          { status: 'DOWN', components: { db: { status: 'DOWN' }, ollama: { status: 'UP' } } },
          { status: 503, statusText: 'Service Unavailable' },
        ),
    );

    expect(pill.state).toBe('database');
    expect(pill.text).toBe('Database unavailable');
  });

  it('reports the backend when it does not answer', async () => {
    const pill = await render((h) => h.expectOne('/actuator/health').error(new ProgressEvent('error'), { status: 0 }));

    expect(pill.state).toBe('backend');
    expect(pill.text).toBe('Backend unreachable');
  });
});
