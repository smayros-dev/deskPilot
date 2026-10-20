import { DOCUMENT, registerLocaleData } from '@angular/common';
import localeAr from '@angular/common/locales/ar';
import localeFr from '@angular/common/locales/fr';
import { Injectable, computed, effect, inject, signal } from '@angular/core';

import { AR } from './i18n/ar';
import { Dictionary, EN, TranslationKey } from './i18n/en';
import { FR } from './i18n/fr';

export type { TranslationKey } from './i18n/en';

export type Lang = 'en' | 'fr' | 'ar';
export type Params = Record<string, string | number>;
export type Translate = (key: TranslationKey, params?: Params) => string;

export const LANGUAGES: { code: Lang; label: string }[] = [
  { code: 'en', label: 'English' },
  { code: 'fr', label: 'Français' },
  { code: 'ar', label: 'العربية' },
];

export const DICTIONARIES: Record<Lang, Dictionary> = { en: EN, fr: FR, ar: AR };

/** Angular locale ids for the date, number and percent pipes. */
const LOCALES: Record<Lang, string> = { en: 'en-US', fr: 'fr', ar: 'ar' };

const STORAGE_KEY = 'deskpilot.lang';

registerLocaleData(localeFr, 'fr');
registerLocaleData(localeAr, 'ar');

/** Replaces {name} placeholders. */
export function interpolate(text: string, params?: Params): string {
  if (!params) {
    return text;
  }
  return text.replace(/\{(\w+)\}/g, (match, name: string) => (name in params ? String(params[name]) : match));
}

/** English texts without the service, e.g. for plain functions and their tests. */
export const translateEn: Translate = (key, params) => interpolate(EN[key], params);

/** The browser's language when it is supported, English otherwise. */
export function preferredLanguage(languages: readonly string[]): Lang {
  for (const language of languages) {
    const code = language.toLowerCase().split('-')[0];
    if (code === 'en' || code === 'fr' || code === 'ar') {
      return code;
    }
  }
  return 'en';
}

/**
 * The UI language as a signal. Templates call {@link t}, which reads the signal, so every view updates when the
 * language changes, without a reload. Arabic switches the page to right-to-left. The choice is kept in localStorage.
 */
@Injectable({ providedIn: 'root' })
export class I18n {
  private readonly document = inject(DOCUMENT);

  private readonly _lang = signal<Lang>(this.initial());
  readonly lang = this._lang.asReadonly();
  readonly dir = computed(() => (this._lang() === 'ar' ? 'rtl' : 'ltr'));
  /** Locale id to pass to the date, number and percent pipes. */
  readonly locale = computed(() => LOCALES[this._lang()]);
  readonly languages = LANGUAGES;

  constructor() {
    effect(() => {
      const root = this.document.documentElement;
      root.lang = this._lang();
      root.dir = this.dir();
      writeStorage(STORAGE_KEY, this._lang());
    });
  }

  t(key: TranslationKey, params?: Params): string {
    return interpolate(DICTIONARIES[this._lang()][key] ?? EN[key], params);
  }

  setLang(lang: Lang): void {
    this._lang.set(lang);
  }

  private initial(): Lang {
    const stored = readStorage(STORAGE_KEY);
    if (stored === 'en' || stored === 'fr' || stored === 'ar') {
      return stored;
    }
    const navigator = this.document.defaultView?.navigator;
    return preferredLanguage(navigator?.languages?.length ? navigator.languages : [navigator?.language ?? 'en']);
  }
}

// Storage can be unavailable (private mode, blocked cookies); the app must work without it.
function readStorage(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeStorage(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    // ignore
  }
}
