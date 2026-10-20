import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { Role } from '../core/auth';
import { Article } from '../core/models';
import { provideFakeAuth } from '../core/testing';
import { KnowledgePage } from './knowledge-page';

const ARTICLES: Article[] = [
  { id: 1, title: 'Connecting to the corporate VPN', tags: 'vpn,network', content: 'Install GlobalProtect.', documentId: null, documentTitle: null },
  { id: 9, title: 'Office hardware: Docking stations', tags: 'office hardware', content: 'Use the USB-C port.', documentId: 3, documentTitle: 'Office hardware' },
];

describe('KnowledgePage', () => {
  let http: HttpTestingController;

  async function render(roles: Role[]) {
    TestBed.configureTestingModule({
      imports: [KnowledgePage],
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting(), provideFakeAuth('someone', roles)],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(KnowledgePage);
    fixture.detectChanges();
    http.expectOne('/api/knowledge-base/articles').flush(ARTICLES);
    if (roles.includes('admin')) {
      http.expectOne('/api/knowledge-base/documents').flush([]);
    }
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => http.verify());

  it('shows which document an article comes from, and keywords for the others', async () => {
    const element = await render(['user']);

    const cards = Array.from(element.querySelectorAll('article.card'));
    expect(cards[0].querySelector('.tags')?.textContent).toContain('vpn');
    expect(cards[1].querySelector('.source')?.textContent).toContain('Office hardware');
    expect(cards[1].querySelector('.tags')).toBeNull();
  });

  it('shows the documents panel to admins only', async () => {
    const userView = await render(['user']);
    expect(userView.querySelector('app-documents-panel')).toBeNull();

    TestBed.resetTestingModule();
    const adminView = await render(['user', 'admin']);
    expect(adminView.querySelector('app-documents-panel')).not.toBeNull();
  });
});
