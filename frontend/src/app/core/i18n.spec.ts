import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { DICTIONARIES, I18n, interpolate, preferredLanguage } from './i18n';
import { EN } from './i18n/en';

describe('I18n', () => {
  const placeholders = (text: string) => (text.match(/\{\w+\}/g) ?? []).sort();

  function create(): I18n {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    const i18n = TestBed.inject(I18n);
    TestBed.tick();
    return i18n;
  }

  afterEach(() => {
    localStorage.clear();
    document.documentElement.lang = 'en';
    document.documentElement.dir = 'ltr';
    vi.restoreAllMocks();
  });

  it.each(['fr', 'ar'] as const)('%s has exactly the English keys and placeholders', (lang) => {
    const dictionary = DICTIONARIES[lang];
    expect(Object.keys(dictionary).sort()).toEqual(Object.keys(EN).sort());
    for (const [key, text] of Object.entries(EN)) {
      const translated = dictionary[key as keyof typeof EN];
      expect(translated.trim(), key).not.toBe('');
      expect(placeholders(translated), key).toEqual(placeholders(text));
    }
  });

  it('picks the first supported browser language', () => {
    expect(preferredLanguage(['de-DE', 'fr-CA', 'en'])).toBe('fr');
    expect(preferredLanguage(['ar-MA'])).toBe('ar');
    expect(preferredLanguage(['de', 'es'])).toBe('en');
    expect(preferredLanguage([])).toBe('en');
  });

  it('fills placeholders and keeps unknown ones', () => {
    expect(interpolate('{a} and {b}', { a: 1 })).toBe('1 and {b}');
  });

  it('defaults to the browser language', () => {
    vi.spyOn(navigator, 'languages', 'get').mockReturnValue(['fr-FR', 'en']);
    const i18n = create();
    expect(i18n.lang()).toBe('fr');
    expect(i18n.t('feedback.thanks')).toBe('Merci pour votre avis');
    expect(i18n.locale()).toBe('fr');
  });

  it('prefers the stored choice over the browser language', () => {
    vi.spyOn(navigator, 'languages', 'get').mockReturnValue(['fr-FR']);
    localStorage.setItem('deskpilot.lang', 'ar');
    expect(create().lang()).toBe('ar');
  });

  it('ignores an unknown stored value', () => {
    vi.spyOn(navigator, 'languages', 'get').mockReturnValue(['en-US']);
    localStorage.setItem('deskpilot.lang', 'xx');
    expect(create().lang()).toBe('en');
  });

  it('switches to Arabic: persists it and sets lang and dir on <html>', () => {
    vi.spyOn(navigator, 'languages', 'get').mockReturnValue(['en-US']);
    const i18n = create();
    expect(document.documentElement.dir).toBe('ltr');

    i18n.setLang('ar');
    TestBed.tick();

    expect(i18n.dir()).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(localStorage.getItem('deskpilot.lang')).toBe('ar');
    expect(i18n.t('nav.chat')).toBe('المحادثة');
  });

  it('still works when storage is blocked', () => {
    vi.spyOn(navigator, 'languages', 'get').mockReturnValue(['ar']);
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const i18n = create();
    expect(i18n.lang()).toBe('ar');
    i18n.setLang('fr');
    TestBed.tick();
    expect(document.documentElement.lang).toBe('fr');
  });
});
